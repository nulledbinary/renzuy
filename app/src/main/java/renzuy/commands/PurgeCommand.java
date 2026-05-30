package renzuy.commands;

import java.time.OffsetDateTime;
import java.util.List;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import org.jetbrains.annotations.NotNull;
import renzuy.commands.text.TextCommand;
import renzuy.ui.Embeds;

/**
 * {@code /purge <count>} (and {@code <prefix>purge <count>}): bulk-deletes
 * the most recent {@code count} messages in the invoking channel.
 *
 * <p>Validation rules — both surfaced to the user as ephemeral errors:
 * <ul>
 *   <li>{@code count} is required (empty input → "value cannot be empty").</li>
 *   <li>{@code count} must be ≥ 1 (zero → "value cannot be zero").</li>
 *   <li>{@code count} is capped at 100 — Discord's bulk-delete API limit.</li>
 *   <li>Messages older than 14 days cannot be bulk-deleted; we trim those
 *       out and report the actual deleted count.</li>
 * </ul>
 */
public final class PurgeCommand extends ListenerAdapter implements TextCommand {

    public static final String NAME = "purge";
    public static final String COUNT_OPTION = "count";
    private static final int MAX_PURGE = 100;
    private static final long BULK_DELETE_AGE_DAYS = 14L;

    @Override
    public void onSlashCommandInteraction(@NotNull SlashCommandInteractionEvent event) {
        if (!event.getName().equals(NAME)) return;

        Guild guild = event.getGuild();
        if (guild == null) {
            event.replyEmbeds(Embeds.warn("This command can only be used in a server."))
                    .setEphemeral(true).queue();
            return;
        }
        Member member = event.getMember();
        if (!Capability.PURGE_MESSAGES.grantedTo(member)) {
            event.replyEmbeds(Embeds.warn("You need the **Manage Messages** permission to use `/purge`."))
                    .setEphemeral(true).queue();
            return;
        }
        OptionMapping countOpt = event.getOption(COUNT_OPTION);
        if (countOpt == null) {
            event.replyEmbeds(Embeds.error("The value cannot be empty. Use `/purge <count>` with a number between 1 and 100."))
                    .setEphemeral(true).queue();
            return;
        }
        int requested = (int) countOpt.getAsLong();
        if (requested == 0) {
            event.replyEmbeds(Embeds.error("The value cannot be zero. Use a number between 1 and 100."))
                    .setEphemeral(true).queue();
            return;
        }
        if (requested < 0) {
            event.replyEmbeds(Embeds.error("The value must be positive. Use a number between 1 and 100."))
                    .setEphemeral(true).queue();
            return;
        }
        int count = Math.min(requested, MAX_PURGE);

        GuildMessageChannel channel = event.getChannel().asGuildMessageChannel();
        event.deferReply(true).queue();
        channel.getHistory().retrievePast(count).queue(messages -> bulkDelete(channel, messages, event), error -> event.getHook().sendMessageEmbeds(Embeds.error("Could not fetch messages: " + error.getMessage())).setEphemeral(true).queue());
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void execute(MessageReceivedEvent event, String args) {
        Member member = event.getMember();
        if (!Capability.PURGE_MESSAGES.grantedTo(member)) {
            event.getMessage().reply("You need the **Manage Messages** permission to use `purge`.")
                    .mentionRepliedUser(false).queue();
            return;
        }
        String trimmed = args == null ? "" : args.strip();
        if (trimmed.isEmpty()) {
            event.getMessage().reply("The value cannot be empty. Usage: `purge <count>` (1–100).")
                    .mentionRepliedUser(false).queue();
            return;
        }
        int requested;
        try {
            requested = Integer.parseInt(trimmed);
        } catch (NumberFormatException e) {
            event.getMessage().reply("`" + trimmed + "` is not a number. Usage: `purge <count>` (1–100).")
                    .mentionRepliedUser(false).queue();
            return;
        }
        if (requested == 0) {
            event.getMessage().reply("The value cannot be zero. Use a number between 1 and 100.")
                    .mentionRepliedUser(false).queue();
            return;
        }
        if (requested < 0) {
            event.getMessage().reply("The value must be positive. Use a number between 1 and 100.")
                    .mentionRepliedUser(false).queue();
            return;
        }
        // +1 so we also sweep the invoking command message itself.
        int count = Math.min(requested + 1, MAX_PURGE);
        GuildMessageChannel channel = event.getChannel().asGuildMessageChannel();
        channel.getHistory().retrievePast(count).queue(
                messages -> bulkDelete(channel, messages, null), error -> event.getMessage().reply("Could not fetch messages: " + error.getMessage()).mentionRepliedUser(false).queue());
    }

    /**
     * Splits messages into bulk-deletable (≤14 days old) and individually-deletable
     * (older), kicks off a bulk-delete on the first set and individual deletes on
     * the second. Reports the total deleted count back to the slash invoker.
     */
    private static void bulkDelete(
            GuildMessageChannel channel, List<Message> messages, SlashCommandInteractionEvent slash) {
        OffsetDateTime cutoff = OffsetDateTime.now().minusDays(BULK_DELETE_AGE_DAYS);
        List<Message> bulkable = messages.stream()
                .filter(m -> m.getTimeCreated().isAfter(cutoff))
                .toList();
        List<Message> tooOld = messages.stream()
                .filter(m -> !m.getTimeCreated().isAfter(cutoff))
                .toList();

        int total = bulkable.size() + tooOld.size();
        if (total == 0) {
            if (slash != null) {
                slash.getHook().sendMessageEmbeds(Embeds.warn("No messages to delete."))
                        .setEphemeral(true).queue();
            }
            return;
        }

        if (bulkable.size() >= 2) {
            channel.deleteMessages(bulkable).queue(
                    v -> tooOld.forEach(m -> m.delete().queue(x -> {}, x -> {})),
                    e -> {
                        if (slash != null) {
                            slash.getHook().sendMessageEmbeds(
                                    Embeds.error("Bulk delete failed: " + e.getMessage()))
                                    .setEphemeral(true).queue();
                        }
                    });
        } else {
            bulkable.forEach(m -> m.delete().queue(x -> {}, x -> {}));
            tooOld.forEach(m   -> m.delete().queue(x -> {}, x -> {}));
        }

        if (slash != null) {
            slash.getHook().sendMessageEmbeds(
                    Embeds.info("Purged **" + total + "** message" + (total == 1 ? "" : "s") + "."))
                    .setEphemeral(true).queue();
        }
    }
}
