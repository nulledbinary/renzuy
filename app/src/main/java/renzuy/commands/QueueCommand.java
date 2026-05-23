package renzuy.commands;

import java.util.List;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import org.jetbrains.annotations.NotNull;
import renzuy.audio.GuildAudioPlayer;
import renzuy.audio.MusicService;
import renzuy.ui.Embeds;
import renzuy.youtube.AudioReference;

/**
 * Shows the queue as an <strong>ephemeral</strong> reply — only the invoker sees it —
 * with Previous / Next buttons for paging through long queues. The ephemeral message
 * inherently scopes the buttons to the same invoker.
 */
public final class QueueCommand extends ListenerAdapter {

    public static final String NAME = "queue";

    private static final int PAGE_SIZE = 10;
    /** customId format: {@code queue:p:<page>}. Page is 0-indexed. */
    private static final String BUTTON_PREFIX = "queue:p:";

    private final MusicService music;

    public QueueCommand(MusicService music) {
        this.music = music;
    }

    @Override
    public void onSlashCommandInteraction(@NotNull SlashCommandInteractionEvent event) {
        if (!event.getName().equals(NAME)) {
            return;
        }
        Guild guild = event.getGuild();
        if (guild == null) {
            event.replyEmbeds(Embeds.warn("This command can only be used in a server."))
                    .setEphemeral(true).queue();
            return;
        }
        GuildAudioPlayer player = music.getOrCreate(guild);
        renderInitial(event, player, 0);
    }

    @Override
    public void onButtonInteraction(@NotNull ButtonInteractionEvent event) {
        String id = event.getComponentId();
        if (!id.startsWith(BUTTON_PREFIX)) {
            return;
        }
        int requestedPage;
        try {
            requestedPage = Integer.parseInt(id.substring(BUTTON_PREFIX.length()));
        } catch (NumberFormatException e) {
            return;
        }
        Guild guild = event.getGuild();
        if (guild == null) {
            return;
        }
        GuildAudioPlayer player = music.getOrCreate(guild);
        renderEdit(event, player, requestedPage);
    }

    private void renderInitial(SlashCommandInteractionEvent event, GuildAudioPlayer player, int requestedPage) {
        AudioReference now = player.nowPlaying();
        List<AudioReference> pending = player.pendingTracks();
        if (now == null && pending.isEmpty()) {
            event.replyEmbeds(Embeds.info("Queue is empty.")).setEphemeral(true).queue();
            return;
        }
        int totalPages = pageCount(pending);
        int page = clamp(requestedPage, totalPages);
        var reply = event.replyEmbeds(buildEmbed(now, pending, page, totalPages)).setEphemeral(true);
        if (totalPages > 1) {
            reply.addComponents(ActionRow.of(prevButton(page), nextButton(page, totalPages)));
        }
        reply.queue();
    }

    private void renderEdit(ButtonInteractionEvent event, GuildAudioPlayer player, int requestedPage) {
        AudioReference now = player.nowPlaying();
        List<AudioReference> pending = player.pendingTracks();
        int totalPages = pageCount(pending);
        int page = clamp(requestedPage, totalPages);
        var edit = event.editMessageEmbeds(buildEmbed(now, pending, page, totalPages));
        if (totalPages > 1) {
            edit = edit.setComponents(ActionRow.of(prevButton(page), nextButton(page, totalPages)));
        } else {
            edit = edit.setComponents(); // drop buttons — only one page now
        }
        edit.queue();
    }

    private static int pageCount(List<AudioReference> pending) {
        return Math.max(1, (pending.size() + PAGE_SIZE - 1) / PAGE_SIZE);
    }

    private static int clamp(int page, int totalPages) {
        if (page < 0) return 0;
        if (page >= totalPages) return totalPages - 1;
        return page;
    }

    private static Button prevButton(int page) {
        return Button.secondary(BUTTON_PREFIX + (page - 1), "◀ Previous").withDisabled(page <= 0);
    }

    private static Button nextButton(int page, int totalPages) {
        return Button.secondary(BUTTON_PREFIX + (page + 1), "Next ▶").withDisabled(page >= totalPages - 1);
    }

    private static MessageEmbed buildEmbed(AudioReference now, List<AudioReference> pending, int page, int totalPages) {
        EmbedBuilder builder = new EmbedBuilder().setColor(Embeds.QUEUED).setTitle("Queue");
        StringBuilder body = new StringBuilder();
        if (now != null) {
            body.append("**Now playing:** ").append(now.title()).append("\n\n");
        }
        if (pending.isEmpty()) {
            body.append("_Queue is empty._");
            builder.setDescription(body.toString());
            return builder.build();
        }
        int start = page * PAGE_SIZE;
        int end = Math.min(start + PAGE_SIZE, pending.size());
        body.append("**Up next** (").append(pending.size()).append(" total):\n");
        for (int i = start; i < end; i++) {
            body.append("`").append(i + 1).append(".` ")
                    .append(pending.get(i).title()).append('\n');
        }
        builder.setDescription(body.toString());
        if (totalPages > 1) {
            builder.setFooter("Page " + (page + 1) + " / " + totalPages);
        }
        return builder.build();
    }
}
