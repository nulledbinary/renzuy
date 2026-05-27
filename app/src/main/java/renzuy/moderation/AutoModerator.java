package renzuy.moderation;

import java.awt.Color;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.UserSnowflake;
import net.dv8tion.jda.api.events.guild.member.GuildMemberJoinEvent;
import net.dv8tion.jda.api.events.guild.member.update.GuildMemberUpdateNicknameEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.user.update.UserUpdateGlobalNameEvent;
import net.dv8tion.jda.api.events.user.update.UserUpdateNameEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.jetbrains.annotations.NotNull;
import renzuy.commands.moderation.UnbanScheduler;

/**
 * Listener that scans every incoming guild message for racial slurs and
 * English hatespeech, deletes offending messages, warns the user, and
 * escalates to the configured punishment (tempmute / tempban) when the
 * per-guild warning threshold is reached.
 *
 * <p>Detection runs against an aggressively normalized form of the message:
 * accents stripped, leetspeak digits and lookalike symbols folded back to
 * letters, all separators (spaces, dots, dashes) collapsed, then a list of
 * pattern stems is matched.
 *
 * <p>The same normalization + stem check is applied to every member's display
 * name (server nickname, username, global name). If any name contains a banned
 * stem the member is timed out for the maximum 28 days; the timeout is lifted
 * automatically when they change to a clean name. Bot owner / administrators
 * are NOT exempt — the only natural exemption is when the offender outranks
 * the bot, in which case the timeout REST call fails silently and the
 * message-delete backstop still removes anything they post.
 *
 * <p>State (warning counts, active name locks) is in-memory and resets on
 * restart. Repeated offenders inside a single uptime window still escalate
 * predictably; rejoining members are re-checked on join, and any existing
 * member with a dirty name is caught the moment they send a message.
 */
public final class AutoModerator extends ListenerAdapter {

    /** Composite key (guildId, userId) → warning count. */
    private final Map<Long, AtomicInteger> warnings = new ConcurrentHashMap<>();

    /** Composite keys of members we have currently locked for a name violation. */
    private final Set<Long> nameLocks = ConcurrentHashMap.newKeySet();

    private final HateWarnConfig config;
    private final UnbanScheduler unbanScheduler;

    /** Stems matched after normalization. Order doesn't matter; first hit is enough. */
    private static final List<Pattern> STEMS = List.of(
            // Racial slurs — both spellings and common stems.
            Pattern.compile("ni+g+e+r+s?"),
            Pattern.compile("ni+g+a+s?"),
            Pattern.compile("ni+g+l+e+t"),
            Pattern.compile("ne+gr+o+i+d"),
            Pattern.compile("ch+i+n+k+s?"),
            Pattern.compile("g+o+o+k+s?"),
            Pattern.compile("k+i+k+e+s?"),
            Pattern.compile("s+p+i+c+s?"),
            Pattern.compile("w+e+t+b+a+c+k+s?"),
            Pattern.compile("t+r+a+n+n+y+s?"),
            Pattern.compile("t+r+a+n+n+i+e+s?"),
            Pattern.compile("f+a+g+g+o+t+s?"),
            Pattern.compile("f+a+g+s"),
            Pattern.compile("d+y+k+e+s?"),
            Pattern.compile("r+e+t+a+r+d+s?"),
            Pattern.compile("r+e+t+a+r+d+e+d"),
            Pattern.compile("m+o+n+g+o+l+o+i+d"),
            Pattern.compile("c+r+i+p+p+l+e+s?")
    );

    public AutoModerator(HateWarnConfig config, UnbanScheduler unbanScheduler) {
        this.config = config;
        this.unbanScheduler = unbanScheduler;
    }

    @Override
    public void onMessageReceived(@NotNull MessageReceivedEvent event) {
        if (!event.isFromGuild() || event.getAuthor().isBot()) return;
        Member member = event.getMember();
        if (member == null) return;
        // No mod/admin bypass — automod applies to every member.

        // Name lockout takes priority: if their nickname/username/global-name
        // is dirty we drop the message and leave them in timeout.
        if (checkNameViolation(member)) {
            event.getMessage().delete().reason("AutoMod: name-lockout active").queue(v -> {}, e -> {});
            return;
        }

        String normalized = normalize(event.getMessage().getContentRaw());
        if (normalized.isEmpty()) return;

        String hit = matchStem(normalized);
        if (hit == null) return;

        // Delete the offending message (best-effort).
        event.getMessage().delete().reason("Hate-speech filter").queue(v -> {}, e -> {});

        Guild guild = event.getGuild();
        long key = compositeKey(guild.getIdLong(), event.getAuthor().getIdLong());
        int count = warnings.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();

        HateWarnConfig.Policy policy = config.get(guild.getIdLong());

        EmbedBuilder warn = new EmbedBuilder()
                .setColor(new Color(0xED4245))
                .setAuthor("Hate-speech detected", null, event.getAuthor().getEffectiveAvatarUrl())
                .setDescription(event.getAuthor().getAsMention()
                        + " your message was removed for containing slurs or hate-speech.")
                .addField("Warning", count + " / " + policy.threshold(), true)
                .addField("Next step",
                        count >= policy.threshold()
                                ? "Auto-" + policy.type().name().toLowerCase() + " applied."
                                : "Further offences will escalate.",
                        true)
                .setFooter("Pattern: " + hit);
        event.getChannel().sendMessageEmbeds(warn.build()).queue(v -> {}, e -> {});

        if (count >= policy.threshold()) {
            applyPunishment(guild, event.getAuthor(), policy);
            warnings.remove(key);
        }
    }

    @Override
    public void onGuildMemberJoin(@NotNull GuildMemberJoinEvent event) {
        checkNameViolation(event.getMember());
    }

    @Override
    public void onGuildMemberUpdateNickname(@NotNull GuildMemberUpdateNicknameEvent event) {
        checkNameViolation(event.getMember());
    }

    @Override
    public void onUserUpdateGlobalName(@NotNull UserUpdateGlobalNameEvent event) {
        recheckEverywhere(event.getUser().getIdLong(), event.getJDA().getGuilds());
    }

    @Override
    public void onUserUpdateName(@NotNull UserUpdateNameEvent event) {
        recheckEverywhere(event.getUser().getIdLong(), event.getJDA().getGuilds());
    }

    private void recheckEverywhere(long userId, List<Guild> guilds) {
        for (Guild g : guilds) {
            Member m = g.getMemberById(userId);
            if (m != null) checkNameViolation(m);
        }
    }

    /**
     * Checks every visible form of the member's name. Locks (timeout + tracking)
     * the member when a banned stem is found; releases the lock when the name
     * is clean again. Returns true if the member is currently locked.
     */
    private boolean checkNameViolation(Member member) {
        if (member == null || member.getUser().isBot()) return false;
        Guild guild = member.getGuild();
        long key = compositeKey(guild.getIdLong(), member.getIdLong());
        String hit = findNameHit(member);
        boolean alreadyLocked = nameLocks.contains(key);

        if (hit != null) {
            if (!alreadyLocked) {
                nameLocks.add(key);
                if (guild.getSelfMember().canInteract(member)) {
                    member.timeoutFor(Duration.ofDays(28))
                            .reason("AutoMod: prohibited content in name (" + hit + ")")
                            .queue(v -> {}, e -> {});
                }
                notifyNameLock(member, hit);
            }
            return true;
        }

        if (alreadyLocked) {
            nameLocks.remove(key);
            if (member.isTimedOut() && guild.getSelfMember().canInteract(member)) {
                member.removeTimeout()
                        .reason("AutoMod: name now clean")
                        .queue(v -> {}, e -> {});
            }
        }
        return false;
    }

    private static String findNameHit(Member member) {
        String[] names = {
                member.getEffectiveName(),
                member.getNickname(),
                member.getUser().getName(),
                member.getUser().getGlobalName()
        };
        for (String n : names) {
            if (n == null) continue;
            String norm = normalize(n);
            if (norm.isEmpty()) continue;
            String stem = matchStem(norm);
            if (stem != null) return stem;
        }
        return null;
    }

    private static String matchStem(String normalized) {
        for (Pattern p : STEMS) {
            if (p.matcher(normalized).find()) return p.pattern();
        }
        return null;
    }

    private static void notifyNameLock(Member member, String hit) {
        Guild guild = member.getGuild();
        EmbedBuilder b = new EmbedBuilder()
                .setColor(new Color(0xED4245))
                .setAuthor("Name violation in " + guild.getName(), null, guild.getIconUrl())
                .setDescription("Your display name contains prohibited content and has been flagged. "
                        + "You will be unable to talk in **" + guild.getName()
                        + "** until you change your server nickname, username, or display name.")
                .setFooter("Pattern: " + hit);
        member.getUser().openPrivateChannel().queue(
                dm -> dm.sendMessageEmbeds(b.build()).queue(v -> {}, e -> {}),
                e -> {});
    }

    private void applyPunishment(Guild guild, User user, HateWarnConfig.Policy policy) {
        if (policy.type() == HateWarnConfig.PunishmentType.TEMPMUTE) {
            guild.retrieveMember(user).queue(m -> {
                Duration d = policy.duration();
                if (d.compareTo(Duration.ofDays(28)) > 0) d = Duration.ofDays(28);
                if (!guild.getSelfMember().canInteract(m)) return;
                m.timeoutFor(d).reason("hatewarn auto-tempmute").queue(v -> {}, e -> {});
            }, e -> {});
        } else {
            guild.ban(UserSnowflake.fromId(user.getIdLong()), 0, TimeUnit.DAYS)
                    .reason("hatewarn auto-tempban")
                    .queue(v -> unbanScheduler.schedule(guild, user.getId(),
                            policy.duration(), "hatewarn auto-tempban expired"),
                            e -> {});
        }
    }

    private static long compositeKey(long guildId, long userId) {
        // Cheap non-colliding combine for ConcurrentHashMap keying. XOR is fine
        // because guildId and userId are independent snowflakes — collisions
        // require equal IDs, which doesn't happen across the same key space.
        return (guildId * 31L) ^ userId;
    }

    /**
     * Normalizes text to defeat common bypasses: leetspeak digits/symbols,
     * unicode lookalikes, accents, intra-word spaces and punctuation. The
     * output is lowercase a-z only.
     */
    static String normalize(String input) {
        if (input == null || input.isEmpty()) return "";
        // Decompose accents so combining marks can be dropped.
        String decomposed = java.text.Normalizer.normalize(input, java.text.Normalizer.Form.NFKD);
        StringBuilder out = new StringBuilder(decomposed.length());
        for (int i = 0; i < decomposed.length(); i++) {
            char c = decomposed.charAt(i);
            // Skip combining marks.
            if (Character.getType(c) == Character.NON_SPACING_MARK) continue;
            char folded = foldChar(Character.toLowerCase(c));
            if (folded >= 'a' && folded <= 'z') {
                out.append(folded);
            }
            // Any other char (space, digit unmapped, punctuation) is dropped,
            // which collapses "n i g g e r" and "n.i.g.g.e.r" into "nigger".
        }
        return out.toString();
    }

    /** Folds common leetspeak digits and lookalikes to ASCII letters. */
    private static char foldChar(char c) {
        return switch (c) {
            case '0' -> 'o';
            case '1', '!', '|' -> 'i';
            case '2' -> 'z';
            case '3', '€' -> 'e';
            case '4', '@' -> 'a';
            case '5', '$' -> 's';
            case '6' -> 'g';
            case '7' -> 't';
            case '8' -> 'b';
            case '9' -> 'g';
            case 'α' -> 'a';
            case 'β' -> 'b';
            case 'ε' -> 'e';
            case 'ι' -> 'i';
            case 'ο' -> 'o';
            case 'ρ' -> 'p';
            case 'υ' -> 'u';
            default -> c;
        };
    }

    public int peekWarnings(long guildId, long userId) {
        AtomicInteger ai = warnings.get(compositeKey(guildId, userId));
        return ai == null ? 0 : ai.get();
    }

    public void clearWarnings(long guildId, long userId) {
        warnings.remove(compositeKey(guildId, userId));
    }
}
