package renzuy.commands;

import java.io.IOException;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.GuildVoiceState;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import org.jetbrains.annotations.NotNull;
import renzuy.audio.GuildAudioPlayer;
import renzuy.audio.MusicService;
import renzuy.commands.text.TextCommand;
import renzuy.ui.Embeds;
import renzuy.youtube.BotChallengeException;
import renzuy.youtube.ResolveResult;
import renzuy.youtube.YoutubeSourceException;

public final class PlayCommand extends ListenerAdapter implements TextCommand {

    public static final String NAME = "play";
    public static final String QUERY_OPTION = "query";

    private final MusicService music;

    public PlayCommand(MusicService music) {
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
        AudioChannel voice = voiceChannelOf(event.getMember());
        if (voice == null) {
            event.replyEmbeds(Embeds.warn("Join a voice channel first.")).setEphemeral(true).queue();
            return;
        }
        OptionMapping option = event.getOption(QUERY_OPTION);
        String query = option != null ? option.getAsString().trim() : "";
        if (query.isEmpty()) {
            event.replyEmbeds(Embeds.warn("You must provide a YouTube URL or a search term."))
                    .setEphemeral(true).queue();
            return;
        }

        event.deferReply(true).queue();
        start(guild, voice, query, embed -> event.getHook().editOriginalEmbeds(embed).queue());
    }

    @Override
    public void execute(MessageReceivedEvent event, String args) {
        Guild guild = event.getGuild();
        AudioChannel voice = voiceChannelOf(event.getMember());
        if (voice == null) {
            event.getChannel().sendMessageEmbeds(Embeds.warn("Join a voice channel first.")).queue();
            return;
        }
        if (args.isEmpty()) {
            event.getChannel().sendMessageEmbeds(
                    Embeds.warn("Provide a YouTube URL, a playlist URL, or a search term.")).queue();
            return;
        }
        start(guild, voice, args, embed -> event.getChannel().sendMessageEmbeds(embed).queue());
    }

    private static AudioChannel voiceChannelOf(Member member) {
        GuildVoiceState state = member != null ? member.getVoiceState() : null;
        return state != null ? state.getChannel() : null;
    }

    private void start(Guild guild, AudioChannel voice, String query, java.util.function.Consumer<MessageEmbed> reply) {
        GuildAudioPlayer player = music.getOrCreate(guild);
        try {
            guild.getAudioManager().openAudioConnection(voice);
        } catch (Exception e) {
            reply.accept(Embeds.error("Could not join voice channel: " + e.getMessage()));
            return;
        }
        Thread worker = new Thread(() -> resolveAndQueue(player, query, reply), "play-resolver");
        worker.setDaemon(true);
        worker.start();
    }

    private void resolveAndQueue(GuildAudioPlayer player, String query, java.util.function.Consumer<MessageEmbed> reply) {
        ResolveResult result;
        try {
            result = music.getSource().resolve(query);
        } catch (BotChallengeException e) {
            // YouTube's bot wall is up for this egress IP. Render a stable one-liner
            // instead of leaking yt-dlp's multi-line stderr to every user that runs
            // /play while it persists. The source layer also pauses YouTube fallback
            // attempts for a few minutes so we stop hammering yt-dlp.
            reply.accept(Embeds.warn(
                    "YouTube is currently blocking this bot's server IP. "
                            + "Playback will retry automatically in a few minutes — "
                            + "or ask the operator to refresh the YouTube cookies."));
            return;
        } catch (YoutubeSourceException e) {
            reply.accept(Embeds.error("Could not resolve: " + e.getMessage()));
            return;
        }
        boolean startedNow;
        try {
            startedNow = player.enqueueAll(result.tracks());
        } catch (IOException e) {
            reply.accept(Embeds.error("Could not start playback: " + e.getMessage()));
            return;
        }
        if (result.isPlaylist()) {
            int firstPosition = startedNow ? 0
                    : Math.max(player.pendingTracks().size() - (result.tracks().size() - 1), 1);
            reply.accept(Embeds.playlistQueued(result.playlistTitle(), result.tracks().size(),
                    result.first(), startedNow, firstPosition));
        } else if (startedNow) {
            reply.accept(Embeds.playing(result.first()));
        } else {
            reply.accept(Embeds.queued(result.first(), player.pendingTracks().size()));
        }
    }
}
