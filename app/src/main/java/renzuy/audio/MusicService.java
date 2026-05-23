package renzuy.audio;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.managers.AudioManager;
import renzuy.DotEnv;
import renzuy.youtube.YoutubeSource;
import renzuy.youtube.YoutubeSourceOptions;

/**
 * Owns the bot-wide {@link YoutubeSource} and one {@link GuildAudioPlayer} per guild.
 *
 * <p>The source is created once and shared: it keeps a warm HTTP/2 connection and a
 * resolution cache, both of which only pay off when reused across every {@code /play}.
 */
public final class MusicService {

    private final YoutubeSource source;
    private final Map<Long, GuildAudioPlayer> players = new ConcurrentHashMap<>();

    public MusicService() {
        // Point the yt-dlp fallback at the bundled binary; the Innertube fast path
        // needs no configuration. Construction prewarms the connection to YouTube.
        //
        // YT_DLP_COOKIES is an optional path to a Netscape-format cookies file. On
        // datacenter IPs (Fargate) YouTube's bot wall fires even when every anonymous
        // client is exhausted; presenting a logged-in cookie jar via --cookies is the
        // most reliable workaround.
        String cookiesPath = DotEnv.get("YT_DLP_COOKIES");
        this.source = new YoutubeSource(YoutubeSourceOptions.builder()
                .ytDlpPath(Binaries.YT_DLP)
                .ytDlpCookiesPath(cookiesPath == null ? "" : cookiesPath)
                .build());
    }

    public YoutubeSource getSource() {
        return source;
    }

    public GuildAudioPlayer getOrCreate(Guild guild) {
        return players.computeIfAbsent(guild.getIdLong(), id -> {
            GuildAudioPlayer player = new GuildAudioPlayer(source::resolveLazy);
            AudioManager manager = guild.getAudioManager();
            manager.setSendingHandler(new PcmAudioSendHandler(player));
            manager.setConnectionListener(new VoiceConnectionLogger(guild.getName()));
            return player;
        });
    }
}
