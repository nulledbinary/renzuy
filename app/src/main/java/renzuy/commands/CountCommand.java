package renzuy.commands;

import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.jetbrains.annotations.NotNull;
import renzuy.counting.CountingStore;
import renzuy.counting.MathExpression;
import renzuy.ui.Embeds;

/**
 * {@code /count}: binds the current channel as the counting-game channel,
 * then referees the game on every message posted there.
 *
 * <p>Rules:
 * <ul>
 *   <li>The next message must equal the current number + 1. Plain numbers and
 *       arithmetic both work — at 76, both {@code 77} and {@code 47+30} count.</li>
 *   <li>Correct counts get a ✅ reaction (and 🎉 on every multiple of 100).</li>
 *   <li>A wrong number resets the run to 0 and the channel starts over at 1.</li>
 *   <li>No double-counting: whoever counted last must wait for someone else
 *       before counting again — breaking that also resets the run.</li>
 *   <li>Messages that aren't numbers/equations (chat, emoji, links) are
 *       ignored, so talking in the channel is safe.</li>
 * </ul>
 *
 * <p>Binding requires <b>Manage Server</b>. Re-running {@code /count} in the
 * bound channel shows the current status; running it elsewhere moves the game
 * there and starts a fresh run. State persists across restarts via
 * {@link CountingStore}.
 */
public final class CountCommand extends ListenerAdapter {

    public static final String NAME = "count";

    private static final Emoji CHECK   = Emoji.fromUnicode("✅");
    private static final Emoji CROSS   = Emoji.fromUnicode("❌");
    private static final Emoji PARTY   = Emoji.fromUnicode("🎉");

    private final CountingStore store;

    public CountCommand(CountingStore store) {
        this.store = store;
    }

    // ---------------- /count: bind / status ----------------

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
        if (!Capability.MANAGE_SERVER.grantedTo(member)) {
            event.replyEmbeds(Embeds.warn("You need **Manage Server** to use `/count`."))
                    .setEphemeral(true).queue();
            return;
        }
        if (!event.getChannel().getType().isMessage()) {
            event.replyEmbeds(Embeds.error("Run `/count` in a normal text channel — that's where the game is played."))
                    .setEphemeral(true).queue();
            return;
        }

        long channelId = event.getChannelIdLong();
        CountingStore.State previous = store.bind(guild.getIdLong(), channelId);

        String body;
        if (previous != null && previous.channelId() == channelId) {
            body = "This is already the counting channel. The run is at **" + previous.count()
                    + "** — next number is **" + (previous.count() + 1) + "**.";
        } else {
            body = (previous == null
                    ? "**Counting game enabled here!**"
                    : "Moved the counting game here from <#" + previous.channelId() + "> — fresh run.")
                    + " Start at **1**. Equations count too (`47+30` = 77),"
                    + " but nobody may count twice in a row, and a wrong number resets the run.";
        }
        event.replyEmbeds(Embeds.info(body)).queue();
    }

    // ---------------- the game ----------------

    @Override
    public void onMessageReceived(@NotNull MessageReceivedEvent event) {
        if (!event.isFromGuild() || event.getAuthor().isBot() || event.isWebhookMessage()) return;

        long guildId = event.getGuild().getIdLong();
        CountingStore.State state = store.get(guildId);
        if (state == null || state.channelId() != event.getChannel().getIdLong()) return;

        String content = event.getMessage().getContentRaw();
        if (!MathExpression.looksLikeAttempt(content)) return;

        Message message = event.getMessage();
        long expected = state.count() + 1;
        Long value = MathExpression.evaluate(content);

        if (value == null || value != expected) {
            store.reset(guildId);
            react(message, CROSS);
            String detail = value == null
                    ? "that's not a valid number or equation"
                    : "that equals **" + value + "**, but the next number was **" + expected + "**";
            message.replyEmbeds(Embeds.error(event.getAuthor().getAsMention()
                            + " ruined the run at **" + state.count() + "** — " + detail
                            + ".\nBack to square one: the next number is **1**."))
                    .mentionRepliedUser(false).queue(v -> {}, err -> {});
            return;
        }

        if (event.getAuthor().getIdLong() == state.lastUserId()) {
            store.reset(guildId);
            react(message, CROSS);
            message.replyEmbeds(Embeds.error(event.getAuthor().getAsMention()
                            + " counted twice in a row — someone else had to go first!"
                            + "\nThe run died at **" + state.count() + "**. Next number is **1**."))
                    .mentionRepliedUser(false).queue(v -> {}, err -> {});
            return;
        }

        store.advance(guildId, expected, event.getAuthor().getIdLong());
        react(message, CHECK);
        if (expected % 100 == 0) {
            react(message, PARTY);
        }
    }

    private static void react(Message message, Emoji emoji) {
        message.addReaction(emoji).queue(v -> {}, err -> {});
    }
}
