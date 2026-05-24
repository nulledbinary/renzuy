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
        // Three optional env knobs feed the bot-wall mitigations in YtDlpFallback;
        // any combination works, and zero is a valid configuration (the fallback
        // still rotates impersonate targets and retries once before tripping).
        //   YT_DLP_COOKIES   — path to a Netscape-format cookies file; flips the
        //                      client order to mweb-first (cookie-aware).
        //   YT_DLP_PROXY     — residential proxy URL passed via --proxy; the
        //                      reliable fix on Fargate egress IPs.
        //   YT_DLP_PO_TOKEN  — GVS PoToken from a real browser session; satisfies
        //                      YouTube's anti-bot without a tracked login.
        String cookiesPath = DotEnv.get("YT_DLP_COOKIES");
        String proxy       = DotEnv.get("YT_DLP_PROXY");
        String poToken     = DotEnv.get("YT_DLP_PO_TOKEN");
        this.source = new YoutubeSource(YoutubeSourceOptions.builder()
                .ytDlpPath(Binaries.YT_DLP)
                .ytDlpCookiesPath(cookiesPath == null ? "" : cookiesPath)
                .ytDlpProxy(proxy == null ? "" : proxy)
                .ytDlpPoToken(poToken == null ? "" : poToken)
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
