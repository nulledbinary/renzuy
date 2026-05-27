package renzuy.moderation;

import java.awt.Color;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.UserSnowflake;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
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
 * pattern stems is matched. This catches bypasses like {@code n1gg3rs},
 * {@code N.I.G.G.E.R}, {@code n i g g e r s}, {@code ｎｉｇｇｅｒ},
 * {@code retαrd}, etc.
 *
 * <p>State (warning counts) is in-memory and resets on restart. Repeated
 * offenders inside a single uptime window still escalate predictably.
 */
public final class AutoModerator extends ListenerAdapter {

    /** Composite key (guildId, userId) → warning count. */
    private final Map<Long, AtomicInteger> warnings = new ConcurrentHashMap<>();

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
            // English hatespeech.
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
        // Moderators get a free pass — assume intent is quoting/discussion.
        if (member.hasPermission(Permission.MODERATE_MEMBERS, Permission.MESSAGE_MANAGE)
                || member.hasPermission(Permission.ADMINISTRATOR)) return;

        String normalized = normalize(event.getMessage().getContentRaw());
        if (normalized.isEmpty()) return;

        String hit = null;
        for (Pattern p : STEMS) {
            if (p.matcher(normalized).find()) {
                hit = p.pattern();
                break;
            }
        }
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
