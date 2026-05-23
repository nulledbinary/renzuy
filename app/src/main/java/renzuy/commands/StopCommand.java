package renzuy.commands;

import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.jetbrains.annotations.NotNull;
import renzuy.audio.GuildAudioPlayer;
import renzuy.audio.MusicService;
import renzuy.commands.text.TextCommand;
import renzuy.ui.Embeds;

public final class StopCommand extends ListenerAdapter implements TextCommand {

    public static final String NAME = "stop";

    private final MusicService music;

    public StopCommand(MusicService music) {
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
        MessageEmbed embed = stopAndBuild(guild);
        event.replyEmbeds(embed).setEphemeral(true).queue();
    }

    @Override
    public void execute(MessageReceivedEvent event, String args) {
        MessageEmbed embed = stopAndBuild(event.getGuild());
        event.getChannel().sendMessageEmbeds(embed).queue();
    }

    private MessageEmbed stopAndBuild(Guild guild) {
        GuildAudioPlayer player = music.getOrCreate(guild);
        if (player.nowPlaying() == null && player.pendingTracks().isEmpty()) {
            return Embeds.warn("Nothing is playing.");
        }
        player.stop();
        guild.getAudioManager().closeAudioConnection();
        return Embeds.stopped();
    }
}
