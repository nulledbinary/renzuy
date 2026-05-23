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
import renzuy.youtube.AudioReference;

public final class SkipCommand extends ListenerAdapter implements TextCommand {

    public static final String NAME = "skip";

    private final MusicService music;

    public SkipCommand(MusicService music) {
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
        event.replyEmbeds(skipAndBuild(guild)).setEphemeral(true).queue();
    }

    @Override
    public void execute(MessageReceivedEvent event, String args) {
        event.getChannel().sendMessageEmbeds(skipAndBuild(event.getGuild())).queue();
    }

    private MessageEmbed skipAndBuild(Guild guild) {
        GuildAudioPlayer player = music.getOrCreate(guild);
        AudioReference skipped = player.skip();
        if (skipped == null) {
            return Embeds.warn("Nothing is playing.");
        }
        AudioReference next = player.nowPlaying();
        return Embeds.skipped(skipped.title(), next != null ? next.title() : null);
    }
}
