package renzuy.commands;

import java.time.Duration;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import org.jetbrains.annotations.NotNull;
import renzuy.commands.moderation.DurationParser;
import renzuy.ui.Embeds;

/**
 * {@code /tempmute @user <duration>}: applies a Discord communication
 * timeout. The platform itself enforces the un-mute when the timeout expires,
 * so there's nothing to schedule on our side.
 *
 * <p>Duration syntax is {@code <number><unit>}: {@code 30s}, {@code 15m},
 * {@code 2h}, {@code 7d}. Discord caps timeouts at 28 days; anything longer
 * is rejected up front rather than silently clamped.
 */
public final class TempMuteCommand extends ListenerAdapter {

    public static final String NAME = "tempmute";
    public static final String USER_OPTION = "user";
    public static final String DURATION_OPTION = "duration";
    public static final String REASON_OPTION = "reason";

    /** Discord's hard cap on the timeout API. */
    private static final Duration MAX_TIMEOUT = Duration.ofDays(28);

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
        if (!Capability.TIMEOUT_MEMBERS.grantedTo(moderator)) {
            event.replyEmbeds(Embeds.warn("You need the **Timeout Members** permission to use `/tempmute`."))
                    .setEphemeral(true).queue();
            return;
        }

        OptionMapping userOpt = event.getOption(USER_OPTION);
        OptionMapping durationOpt = event.getOption(DURATION_OPTION);
        if (userOpt == null || durationOpt == null) {
            event.replyEmbeds(Embeds.error("Usage: `/tempmute <user> <duration>` — duration like `30s`, `15m`, `2h`, `7d`."))
                    .setEphemeral(true).queue();
            return;
        }
        User target = userOpt.getAsUser();
        if (target.getIdLong() == event.getJDA().getSelfUser().getIdLong()) {
            event.replyEmbeds(Embeds.error("I can't time myself out."))
                    .setEphemeral(true).queue();
            return;
        }
        Duration duration;
        try {
            duration = DurationParser.parseOrThrow(durationOpt.getAsString());
        } catch (DurationParser.InvalidDurationException e) {
            event.replyEmbeds(Embeds.error(e.getMessage()))
                    .setEphemeral(true).queue();
            return;
        }
        if (duration.compareTo(MAX_TIMEOUT) > 0) {
            event.replyEmbeds(Embeds.error("Discord caps timeouts at 28 days. Use `28d` or less."))
                    .setEphemeral(true).queue();
            return;
        }

        String reason = optionalReason(event, moderator);
        event.deferReply().queue();
        guild.retrieveMember(target).queue(
                member -> applyTimeout(event, member, duration, reason),
                error  -> event.getHook().sendMessageEmbeds(
                        Embeds.error("Could not find that user in this server."))
                        .queue());
    }

    private static String optionalReason(SlashCommandInteractionEvent event, Member moderator) {
        OptionMapping r = event.getOption(REASON_OPTION);
        String supplied = r == null ? "" : r.getAsString().strip();
        String by = moderator == null ? "unknown" : moderator.getUser().getName();
        return supplied.isEmpty() ? "tempmute by " + by : supplied + " (by " + by + ")";
    }

    private static void applyTimeout(
            SlashCommandInteractionEvent event, Member target, Duration duration, String reason) {
        if (!event.getGuild().getSelfMember().canInteract(target)) {
            event.getHook().sendMessageEmbeds(
                    Embeds.error("I can't time out **" + target.getUser().getName()
                            + "** — their highest role is at or above mine."))
                    .queue();
            return;
        }
        target.timeoutFor(duration).reason(reason).queue(
                v -> event.getHook().sendMessageEmbeds(
                        Embeds.info("Timed out " + target.getAsMention() + " for **"
                                + DurationParser.humanize(duration) + "**.")).queue(),
                err -> event.getHook().sendMessageEmbeds(
                        Embeds.error("Timeout failed: " + err.getMessage())).queue());
    }
}
