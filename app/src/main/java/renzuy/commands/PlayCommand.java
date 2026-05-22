package renzuy.commands;

import java.io.IOException;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.GuildVoiceState;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import org.jetbrains.annotations.NotNull;
import renzuy.audio.GuildAudioPlayer;
import renzuy.audio.MusicService;
import renzuy.youtube.AudioReference;
import renzuy.youtube.YoutubeSourceException;

public final class PlayCommand extends ListenerAdapter {

    public static final String NAME = "play";
    public static final String QUERY_OPTION = "query";

    private final MusicService music;

    public PlayCommand(MusicService music) {
        this.music = music;
    }

    @Override
    public void onSlashCommandInteraction(@NotNull SlashCommandInteractionEvent event) {
        if (!event.getName().equals(NAME)) {
            return;
        }

        Guild guild = event.getGuild();
        if (guild == null) {
            event.reply("This command can only be used in a server.").setEphemeral(true).queue();
            return;
        }

        Member member = event.getMember();
        GuildVoiceState voiceState = member != null ? member.getVoiceState() : null;
        AudioChannel voiceChannel = voiceState != null ? voiceState.getChannel() : null;
        if (voiceChannel == null) {
            event.reply("Join a voice channel first.").setEphemeral(true).queue();
            return;
        }

        OptionMapping queryOption = event.getOption(QUERY_OPTION);
        String query = queryOption != null ? queryOption.getAsString().trim() : "";
        if (query.isEmpty()) {
            event.reply("You must provide a YouTube URL or a search term.")
                    .setEphemeral(true).queue();
            return;
        }

        event.deferReply().queue();

        GuildAudioPlayer player = music.getOrCreate(guild);

        try {
            guild.getAudioManager().openAudioConnection(voiceChannel);
        } catch (Exception e) {
            event.getHook().sendMessage("Could not join voice channel: " + e.getMessage()).queue();
            return;
        }

        // Resolution can block (warm HTTP request, or a yt-dlp subprocess on the
        // fallback path) — keep it off the JDA event thread.
        Thread worker = new Thread(() -> resolveAndQueue(event, player, query), "play-resolver");
        worker.setDaemon(true);
        worker.start();
    }

    private void resolveAndQueue(SlashCommandInteractionEvent event, GuildAudioPlayer player, String query) {
        AudioReference track;
        try {
            track = music.getSource().resolve(query);
        } catch (YoutubeSourceException e) {
            event.getHook().sendMessage("Could not resolve: " + e.getMessage()).queue();
            return;
        }

        boolean startedNow;
        try {
            startedNow = player.enqueue(track);
        } catch (IOException e) {
            event.getHook().sendMessage("Could not start playback: " + e.getMessage()).queue();
            return;
        }

        String prefix = startedNow ? "Now playing: " : "Queued: ";
        event.getHook().sendMessage(prefix + "**" + track.title() + "**").queue();
    }
}
