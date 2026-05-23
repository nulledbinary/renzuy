package renzuy.commands;

import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import org.jetbrains.annotations.NotNull;
import renzuy.audio.GuildAudioPlayer;
import renzuy.audio.MusicService;
import renzuy.commands.text.TextCommand;
import renzuy.ui.Embeds;
import renzuy.youtube.AudioReference;

public final class RemoveCommand extends ListenerAdapter implements TextCommand {

    public static final String NAME = "remove";
    public static final String POSITION_OPTION = "position";

    private final MusicService music;

    public RemoveCommand(MusicService music) {
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
        OptionMapping option = event.getOption(POSITION_OPTION);
        if (option == null) {
            event.replyEmbeds(Embeds.warn("You must provide a queue position to remove."))
                    .setEphemeral(true).queue();
            return;
        }
        event.replyEmbeds(removeAndBuild(guild, (int) option.getAsLong())).setEphemeral(true).queue();
    }

    @Override
    public void execute(MessageReceivedEvent event, String args) {
        int position;
        try {
            position = Integer.parseInt(args.strip());
        } catch (NumberFormatException e) {
            event.getChannel().sendMessageEmbeds(
                    Embeds.warn("Usage: `<prefix>remove <position>` — see the queue for positions.")).queue();
            return;
        }
        event.getChannel().sendMessageEmbeds(removeAndBuild(event.getGuild(), position)).queue();
    }

    private MessageEmbed removeAndBuild(Guild guild, int position) {
        GuildAudioPlayer player = music.getOrCreate(guild);
        AudioReference removed = player.remove(position);
        if (removed == null) {
            int size = player.pendingTracks().size();
            String detail = size == 0
                    ? "The queue is empty."
                    : "Position must be between 1 and " + size + ".";
            return Embeds.warn("Invalid queue position. " + detail);
        }
        return Embeds.removed(removed.title(), position);
    }
}
