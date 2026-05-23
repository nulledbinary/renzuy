package renzuy.commands;

import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.jetbrains.annotations.NotNull;
import renzuy.audio.GuildAudioPlayer;
import renzuy.audio.MusicService;
import renzuy.ui.Embeds;

/** Stops the current track and clears the pending queue. */
public final class StopCommand extends ListenerAdapter {

    public static final String NAME = "stop";

    private final MusicService music;

    public StopCommand(MusicService music) {
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
        if (player.nowPlaying() == null && player.pendingTracks().isEmpty()) {
            event.replyEmbeds(Embeds.warn("Nothing is playing.")).setEphemeral(true).queue();
            return;
        }
        player.stop();
        guild.getAudioManager().closeAudioConnection();
        event.replyEmbeds(Embeds.stopped()).setEphemeral(true).queue();
    }
}
