package renzuy.commands;

import java.util.List;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.jetbrains.annotations.NotNull;
import renzuy.audio.GuildAudioPlayer;
import renzuy.audio.MusicService;
import renzuy.commands.text.TextCommand;
import renzuy.ui.Embeds;
import renzuy.youtube.AudioReference;

public final class QueueCommand extends ListenerAdapter implements TextCommand {

    public static final String NAME = "queue";

    private static final int PAGE_SIZE = 10;
    private static final String BUTTON_PREFIX = "queue:p:";

    private final MusicService music;

    public QueueCommand(MusicService music) {
        this.music = music;
    }

    @Override
    public String name() {
        return NAME;
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
        GuildAudioPlayer player = music.getOrCreate(guild);
        renderSlash(event, player, 0);
    }

    @Override
    public void onButtonInteraction(@NotNull ButtonInteractionEvent event) {
        String id = event.getComponentId();
        if (!id.startsWith(BUTTON_PREFIX)) return;
        int requested;
        try {
            requested = Integer.parseInt(id.substring(BUTTON_PREFIX.length()));
        } catch (NumberFormatException e) {
            return;
        }
        Guild guild = event.getGuild();
        if (guild == null) return;
        GuildAudioPlayer player = music.getOrCreate(guild);
        AudioReference now = player.nowPlaying();
        List<AudioReference> pending = player.pendingTracks();
        int totalPages = pageCount(pending);
        int page = clamp(requested, totalPages);
        var edit = event.editMessageEmbeds(buildEmbed(now, pending, page, totalPages));
        edit = totalPages > 1
                ? edit.setComponents(ActionRow.of(prevButton(page), nextButton(page, totalPages)))
                : edit.setComponents();
        edit.queue();
    }

    @Override
    public void execute(MessageReceivedEvent event, String args) {
        GuildAudioPlayer player = music.getOrCreate(event.getGuild());
        AudioReference now = player.nowPlaying();
        List<AudioReference> pending = player.pendingTracks();
        if (now == null && pending.isEmpty()) {
            event.getChannel().sendMessageEmbeds(Embeds.info("Queue is empty.")).queue();
            return;
        }
        int totalPages = pageCount(pending);
        // Text path doesn't get button paging — show the first page; users can re-run or use /queue.
        event.getChannel().sendMessageEmbeds(buildEmbed(now, pending, 0, totalPages)).queue();
    }

    private void renderSlash(SlashCommandInteractionEvent event, GuildAudioPlayer player, int requestedPage) {
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
        EmbedBuilder builder = new EmbedBuilder().setColor(Embeds.QUEUED).setTitle("🎶 Player Queue");
        
        if (now != null) {
            builder.addField("▶️ Now Playing", "**[" + Embeds.escape(now.title()) + "](" + now.webpageUrl() + ")**\n`" + Embeds.escape(now.author()) + "`", false);
            if (now.thumbnailUrl() != null) {
                builder.setThumbnail(now.thumbnailUrl());
            }
        }
        
        StringBuilder body = new StringBuilder();
        if (pending.isEmpty()) {
            if (now == null) {
                body.append("_Queue is empty._");
            } else {
                body.append("\n_No more tracks in queue._");
            }
        } else {
            int start = page * PAGE_SIZE;
            int end = Math.min(start + PAGE_SIZE, pending.size());
            body.append("📜 **Up Next** (").append(pending.size()).append(" total):\n\n");
            for (int i = start; i < end; i++) {
                body.append("`").append(i + 1).append(".` **").append(Embeds.escape(pending.get(i).title())).append("**\n");
            }
        }
        
        if (body.length() > 0) {
            builder.setDescription(body.toString());
        }
        if (totalPages > 1) {
            builder.setFooter("Page " + (page + 1) + " of " + totalPages, "https://cdn.jsdelivr.net/gh/twitter/twemoji@14.0.2/assets/72x72/1f4cb.png");
        }
        return builder.build();
    }
}
