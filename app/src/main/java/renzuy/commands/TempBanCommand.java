package renzuy.commands;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.UserSnowflake;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import org.jetbrains.annotations.NotNull;
import renzuy.commands.moderation.DurationParser;
import renzuy.commands.moderation.UnbanScheduler;
import renzuy.ui.Embeds;

/**
 * {@code /tempban @user <duration>}: bans the user, schedules an auto-unban
 * after the given duration. Same duration syntax as {@code /tempmute} —
 * {@code 30s}, {@code 15m}, {@code 2h}, {@code 7d} — with no upper cap since
 * a true permanent ban is achievable via {@code 365d}+ and we shouldn't surprise
 * the moderator by clamping.
 *
 * <p>The unban is in-memory ({@link UnbanScheduler}); a bot restart will leave
 * the user banned until manually unbanned. This is the documented behaviour.
 */
public final class TempBanCommand extends ListenerAdapter {

    public static final String NAME = "tempban";
    public static final String USER_OPTION = "user";
    public static final String DURATION_OPTION = "duration";
    public static final String REASON_OPTION = "reason";

    /** How much of the user's recent message history to also delete on ban. */
    private static final int DEFAULT_DELETE_MESSAGE_DAYS = 0;

    private final UnbanScheduler scheduler;

    public TempBanCommand(UnbanScheduler scheduler) {
        this.scheduler = scheduler;
    }

    @Override
    public void onSlashCommandInteraction(@NotNull SlashCommandInteractionEvent event) {
        if (!event.getName().equals(NAME)) return;

        Guild guild = event.getGuild();
        if (guild == null) {
            event.replyEmbeds(Embeds.warn("This command can only be used in a server."))
                    .setEphemeral(true).queue();
            return;
        }
        Member moderator = event.getMember();
        if (!Capability.BAN_MEMBERS.grantedTo(moderator)) {
            event.replyEmbeds(Embeds.warn("You need the **Ban Members** permission to use `/tempban`."))
                    .setEphemeral(true).queue();
            return;
        }

        OptionMapping userOpt = event.getOption(USER_OPTION);
        OptionMapping durationOpt = event.getOption(DURATION_OPTION);
        if (userOpt == null || durationOpt == null) {
            event.replyEmbeds(Embeds.error("Usage: `/tempban <user> <duration>` — duration like `30m`, `2h`, `7d`."))
                    .setEphemeral(true).queue();
            return;
        }
        User target = userOpt.getAsUser();
        if (target.getIdLong() == event.getJDA().getSelfUser().getIdLong()) {
            event.replyEmbeds(Embeds.error("I refuse to ban myself.")).setEphemeral(true).queue();
            return;
        }
        if (moderator != null && target.getIdLong() == moderator.getIdLong()) {
            event.replyEmbeds(Embeds.error("You can't tempban yourself.")).setEphemeral(true).queue();
            return;
        }
        Duration duration;
        try {
            duration = DurationParser.parseOrThrow(durationOpt.getAsString());
        } catch (DurationParser.InvalidDurationException e) {
            event.replyEmbeds(Embeds.error(e.getMessage())).setEphemeral(true).queue();
            return;
        }

        String reason = optionalReason(event, moderator, duration);
        event.deferReply().queue();
        // If we can pull a Member object we can role-check; if not (user not in
        // guild yet) we ban-by-ID, which Discord allows for moderation purposes.
        guild.retrieveMember(target).queue(
                member -> banMember(event, guild, member, duration, reason),
                err    -> banById(event, guild, target, duration, reason));
    }

    private static String optionalReason(
            SlashCommandInteractionEvent event, Member moderator, Duration duration) {
        OptionMapping r = event.getOption(REASON_OPTION);
        String supplied = r == null ? "" : r.getAsString().strip();
        String by = moderator == null ? "unknown" : moderator.getUser().getName();
        String head = supplied.isEmpty() ? "tempban" : supplied;
        return head + " (by " + by + ", " + DurationParser.humanize(duration) + ")";
    }

    private void banMember(
            SlashCommandInteractionEvent event, Guild guild,
            Member target, Duration duration, String reason) {
        if (!guild.getSelfMember().canInteract(target)) {
            event.getHook().sendMessageEmbeds(
                    Embeds.error("I can't ban **" + target.getUser().getName()
                            + "** — their highest role is at or above mine."))
                    .queue();
            return;
        }
        banById(event, guild, target.getUser(), duration, reason);
    }

    private void banById(
            SlashCommandInteractionEvent event, Guild guild,
            User target, Duration duration, String reason) {
        guild.ban(UserSnowflake.fromId(target.getIdLong()),
                        DEFAULT_DELETE_MESSAGE_DAYS, TimeUnit.DAYS)
                .reason(reason)
                .queue(
                        v -> {
                            scheduler.schedule(guild, target.getId(), duration, "tempban expired: " + reason);
                            event.getHook().sendMessageEmbeds(
                                    Embeds.info("Tempbanned **" + target.getName() + "** for **"
                                            + DurationParser.humanize(duration) + "**.")).queue();
                        },
                        err -> event.getHook().sendMessageEmbeds(
                                Embeds.error("Ban failed: " + err.getMessage())).queue());
    }
}
