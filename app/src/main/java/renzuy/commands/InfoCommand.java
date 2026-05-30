package renzuy.commands;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.List;
import java.util.stream.Collectors;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import org.jetbrains.annotations.NotNull;
import renzuy.commands.text.TextCommand;
import renzuy.ui.Embeds;

/**
 * {@code /info <user>} and {@code <prefix>info <id|tag|mention>}: builds a deep
 * user-profile embed for the resolved user.
 *
 * <p>Where the legacy version stopped at username + roles + join dates, this
 * surfaces account flags, server tenure (days), boost status, the user's
 * highest role with color, the moderation-relevant permission subset, and
 * voice-channel state. The bot's reply latency is still footered so admins
 * can sanity-check gateway responsiveness.
 */
public final class InfoCommand extends ListenerAdapter implements TextCommand {

    public static final String NAME = "info";
    public static final String OPTION = "user";

    /** The permissions worth surfacing on the info card — anything else is noise. */
    private static final List<Permission> KEY_PERMS = List.of(
            Permission.ADMINISTRATOR,
            Permission.MANAGE_SERVER,
            Permission.BAN_MEMBERS,
            Permission.KICK_MEMBERS,
            Permission.MODERATE_MEMBERS,
            Permission.MESSAGE_MANAGE,
            Permission.MANAGE_ROLES,
            Permission.MANAGE_CHANNEL,
            Permission.VIEW_AUDIT_LOGS,
            Permission.MESSAGE_MENTION_EVERYONE);

    // ---------------- Slash entry ----------------

    @Override
    public void onSlashCommandInteraction(@NotNull SlashCommandInteractionEvent event) {
        if (!event.getName().equals(NAME)) return;
        Guild guild = event.getGuild();
        if (guild == null) {
            event.replyEmbeds(Embeds.warn("This command can only be used in a server."))
                    .setEphemeral(true).queue();
            return;
        }

        long start = System.currentTimeMillis();
        OptionMapping option = event.getOption(OPTION);
        User user = option != null ? option.getAsUser() : event.getUser();

        resolveMemberAndReplySlash(event, guild, user, start);
    }

    private static void resolveMemberAndReplySlash(
            SlashCommandInteractionEvent event, Guild guild, User user, long startMillis) {
        // Retrieve user (REST) and profile (banner/accent color) in sequence,
        // then retrieve the Member so we can show role/voice/perm data too.
        user.getJDA().retrieveUserById(user.getIdLong()).queue(
            fresh -> fresh.retrieveProfile().queue(
                profile -> guild.retrieveMember(fresh).queue(
                    member -> event.replyEmbeds(buildEmbed(fresh, member, guild, profile, System.currentTimeMillis() - startMillis)).setEphemeral(true).queue(),
                    err    -> event.replyEmbeds(buildEmbed(fresh, null, guild, profile, System.currentTimeMillis() - startMillis)).setEphemeral(true).queue()),
                err -> guild.retrieveMember(fresh).queue(
                    member -> event.replyEmbeds(buildEmbed(fresh, member, guild, null, System.currentTimeMillis() - startMillis)).setEphemeral(true).queue(),
                    e2     -> event.replyEmbeds(buildEmbed(fresh, null, guild, null, System.currentTimeMillis() - startMillis)).setEphemeral(true).queue())),
            err -> event.replyEmbeds(buildEmbed(user, null, guild, null, System.currentTimeMillis() - startMillis)).setEphemeral(true).queue());
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void execute(MessageReceivedEvent event, String args) {
        long start = System.currentTimeMillis();
        Guild guild = event.getGuild();
        if (args.isEmpty()) {
            User self = event.getAuthor();
            self.getJDA().retrieveUserById(self.getIdLong()).queue(
                fresh -> fresh.retrieveProfile().queue(
                    profile -> guild.retrieveMember(fresh).queue(
                        m   -> reply(event, fresh, m, guild, profile, System.currentTimeMillis() - start),
                        err -> reply(event, fresh, null, guild, profile, System.currentTimeMillis() - start)),
                    err -> guild.retrieveMember(fresh).queue(
                        m   -> reply(event, fresh, m, guild, null, System.currentTimeMillis() - start),
                        e2  -> reply(event, fresh, null, guild, null, System.currentTimeMillis() - start))),
                err -> reply(event, self, null, guild, null, System.currentTimeMillis() - start));
            return;
        }
        resolveUser(guild, args).queue(
                user -> user.retrieveProfile().queue(
                    profile -> guild.retrieveMember(user).queue(
                        m   -> reply(event, user, m, guild, profile, System.currentTimeMillis() - start),
                        err -> reply(event, user, null, guild, profile, System.currentTimeMillis() - start)),
                    err -> guild.retrieveMember(user).queue(
                        m   -> reply(event, user, m, guild, null, System.currentTimeMillis() - start),
                        e2  -> reply(event, user, null, guild, null, System.currentTimeMillis() - start))),
                err -> event.getChannel().sendMessageEmbeds(Embeds.warn("Could not find that user. Pass a user ID, @mention, or `name#1234` tag.")).queue());
    }

    private static net.dv8tion.jda.api.requests.RestAction<User> resolveUser(Guild guild, String raw) {
        String token = raw.strip();
        if (token.startsWith("<@") && token.endsWith(">")) {
            String inner = token.substring(2, token.length() - 1);
            if (inner.startsWith("!")) inner = inner.substring(1);
            if (inner.chars().allMatch(Character::isDigit)) {
                return guild.getJDA().retrieveUserById(inner);
            }
        }
        if (token.chars().allMatch(Character::isDigit) && token.length() >= 17) {
            return guild.getJDA().retrieveUserById(token);
        }
        int hash = token.indexOf('#');
        String username = hash < 0 ? token : token.substring(0, hash);
        Member match = guild.getMembersByName(username, true).stream().findFirst().orElse(null);
        if (match == null) {
            match = guild.getMembersByEffectiveName(username, true).stream().findFirst().orElse(null);
        }
        if (match != null) {
            return guild.getJDA().retrieveUserById(match.getIdLong());
        }
        return guild.getJDA().retrieveUserById(token);
    }

    private static void reply(MessageReceivedEvent event, User user, Member member, Guild guild,
                              User.Profile profile, long latencyMillis) {
        event.getChannel().sendMessageEmbeds(buildEmbed(user, member, guild, profile, latencyMillis)).queue();
    }

    // ---------------- Embed ----------------

    private static MessageEmbed buildEmbed(User user, Member member, Guild guild,
                                           User.Profile profile, long latencyMillis) {
        OffsetDateTime created = user.getTimeCreated().withOffsetSameInstant(ZoneOffset.UTC);
        long accountAgeDays = ChronoUnit.DAYS.between(created.toLocalDate(), OffsetDateTime.now(ZoneOffset.UTC).toLocalDate());

        java.awt.Color embedColor;
        if (profile != null && profile.getAccentColor() != null) {
            embedColor = profile.getAccentColor();
        } else if (member != null && member.getColorRaw() != Role.DEFAULT_COLOR_RAW) {
            embedColor = new java.awt.Color(member.getColorRaw());
        } else {
            embedColor = Embeds.INFO;
        }

        EmbedBuilder b = new EmbedBuilder()
                .setColor(embedColor)
                .setAuthor(user.getName() + (user.isBot() ? " (bot)" : ""), null, user.getEffectiveAvatarUrl())
                .setThumbnail(user.getEffectiveAvatarUrl())
                .addField("👤 User", user.getAsMention() + "\n`" + user.getId() + "`", true);

        String globalName = user.getGlobalName();
        if (globalName != null && !globalName.isBlank() && !globalName.equals(user.getName())) {
            b.addField("🏷️ Global name", escape(globalName), true);
        }
        b.addField("🆔 Username", "`" + user.getName() + "`", true);
        String discriminator = user.getDiscriminator();
        if (discriminator != null && !"0000".equals(discriminator) && !"0".equals(discriminator)) {
            b.addField("🔢 Discriminator", "`#" + discriminator + "`", true);
        }

        if (member != null) {
            b.addField("📛 Display name", escape(member.getEffectiveName()), true);
            b.addField("💭 Status", member.getOnlineStatus().getKey(), true);
        } else {
            b.addField("📛 Display name", escape(user.getName()), true);
            b.addField("💭 Status", "not in server", true);
        }

        if (profile != null) {
            if (profile.getBannerUrl() != null) {
                b.setImage(profile.getBannerUrl() + "?size=600");
            }
            if (profile.getAccentColor() != null) {
                String hex = String.format("#%06X", 0xFFFFFF & profile.getAccentColor().getRGB());
                b.addField("🎨 Accent color", "`" + hex + "`", true);
            }
        }

        long mutuals = user.getMutualGuilds().size();
        if (mutuals > 0) {
            b.addField("🤝 Mutual servers (bot-visible)", String.valueOf(mutuals), true);
        }

        b.addField("📅 Account created",
                "<t:" + created.toEpochSecond() + ":D> (<t:" + created.toEpochSecond() + ":R>)\n"
                        + accountAgeDays + " days old",
                false);

        if (member != null) {
            OffsetDateTime joined = member.getTimeJoined().withOffsetSameInstant(ZoneOffset.UTC);
            long tenureDays = ChronoUnit.DAYS.between(joined.toLocalDate(), OffsetDateTime.now(ZoneOffset.UTC).toLocalDate());
            b.addField("🚪 Joined this server",
                    "<t:" + joined.toEpochSecond() + ":D> (<t:" + joined.toEpochSecond() + ":R>)\n"
                            + tenureDays + " days in server",
                    false);

            OffsetDateTime boostingSince = member.getTimeBoosted();
            if (boostingSince != null) {
                b.addField("💎 Server booster", "since <t:" + boostingSince.toEpochSecond() + ":R>", true);
            }

            OffsetDateTime timeoutEnd = member.getTimeOutEnd();
            if (timeoutEnd != null && timeoutEnd.isAfter(OffsetDateTime.now())) {
                b.addField("🔇 Timed out", "ends <t:" + timeoutEnd.toEpochSecond() + ":R>", true);
            }

            if (member.getVoiceState() != null && member.getVoiceState().getChannel() != null) {
                b.addField("🔊 Voice", "in " + member.getVoiceState().getChannel().getAsMention(), true);
            }

            Role highest = member.getRoles().isEmpty() ? null : member.getRoles().get(0);
            if (highest != null) {
                b.addField("👑 Highest role", highest.getAsMention(), true);
            }

            String roles = member.getRoles().isEmpty()
                    ? "—"
                    : member.getRoles().stream().limit(20).map(Role::getAsMention).collect(Collectors.joining(" "));
            if (member.getRoles().size() > 20) {
                roles += " (+" + (member.getRoles().size() - 20) + " more)";
            }
            b.addField("🎭 Roles (" + member.getRoles().size() + ")", roles, false);

            String perms = keyPermissions(member);
            if (!perms.isEmpty()) {
                b.addField("🔑 Key permissions", perms, false);
            }
        }

        String flags = userFlags(user);
        if (!flags.isEmpty()) {
            b.addField("🚩 Account flags", flags, false);
        }

        b.setFooter("Ran for " + latencyMillis + " ms");
        return b.build();
    }

    private static String keyPermissions(Member member) {
        EnumSet<Permission> have = EnumSet.copyOf(member.getPermissions());
        StringBuilder sb = new StringBuilder();
        for (Permission p : KEY_PERMS) {
            if (have.contains(p)) {
                if (sb.length() > 0) sb.append(", ");
                sb.append('`').append(p.getName()).append('`');
            }
        }
        return sb.toString();
    }

    private static String userFlags(User user) {
        EnumSet<User.UserFlag> flags = EnumSet.copyOf(user.getFlags());
        if (flags.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (User.UserFlag f : flags) {
            if (sb.length() > 0) sb.append(", ");
            sb.append('`').append(f.getName()).append('`');
        }
        return sb.toString();
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("*", "\\*")
                .replace("_", "\\_")
                .replace("`", "\\`")
                .replace("~", "\\~");
    }
}
