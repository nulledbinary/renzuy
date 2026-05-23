package renzuy.commands;

import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.jetbrains.annotations.NotNull;
import renzuy.audio.GuildAudioPlayer;
import renzuy.audio.MusicService;
import renzuy.ui.Embeds;
import renzuy.youtube.AudioReference;

/** Stops the currently-playing track and starts the next pending one (if any). */
public final class SkipCommand extends ListenerAdapter {

    public static final String NAME = "skip";

    private final MusicService music;

    public SkipCommand(MusicService music) {
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
        AudioReference skipped = player.skip();
        if (skipped == null) {
            event.replyEmbeds(Embeds.warn("Nothing is playing.")).setEphemeral(true).queue();
            return;
        }
        AudioReference next = player.nowPlaying();
        event.replyEmbeds(Embeds.skipped(skipped.title(), next != null ? next.title() : null))
                .setEphemeral(true).queue();
    }
}
