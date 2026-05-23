package renzuy.commands;

import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import org.jetbrains.annotations.NotNull;
import renzuy.audio.GuildAudioPlayer;
import renzuy.audio.MusicService;
import renzuy.ui.Embeds;
import renzuy.youtube.AudioReference;

/** Removes a track from the pending queue by its 1-based position (as shown by /queue). */
public final class RemoveCommand extends ListenerAdapter {

    public static final String NAME = "remove";
    public static final String POSITION_OPTION = "position";

    private final MusicService music;

    public RemoveCommand(MusicService music) {
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
        OptionMapping positionOption = event.getOption(POSITION_OPTION);
        if (positionOption == null) {
            event.replyEmbeds(Embeds.warn("You must provide a queue position to remove."))
                    .setEphemeral(true).queue();
            return;
        }
        int position = (int) positionOption.getAsLong();

        GuildAudioPlayer player = music.getOrCreate(guild);
        AudioReference removed = player.remove(position);
        if (removed == null) {
            int size = player.pendingTracks().size();
            String detail = size == 0
                    ? "The queue is empty."
                    : "Position must be between 1 and " + size + ".";
            event.replyEmbeds(Embeds.warn("Invalid queue position. " + detail))
                    .setEphemeral(true).queue();
            return;
        }
        event.replyEmbeds(Embeds.removed(removed.title(), position))
                .setEphemeral(true).queue();
    }
}
