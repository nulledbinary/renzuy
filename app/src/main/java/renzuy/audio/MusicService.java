package renzuy.audio;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.managers.AudioManager;
import renzuy.DotEnv;
import renzuy.youtube.ProxyUrl;
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
    private final String proxyUrl;
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
        //   YT_DLP_PROXY     — residential/ISP proxy URL (http://user:pass@host:port).
        //                      Routes the WHOLE pipeline — Innertube, yt-dlp and the
        //                      ffmpeg media fetch — through one egress IP; the
        //                      reliable fix on Fargate egress IPs.
        //   YT_DLP_PO_TOKEN  — GVS PoToken from a real browser session; satisfies
        //                      YouTube's anti-bot without a tracked login.
        //   YT_DLP_POT_PROVIDER_URL — URL of a bgutil-ytdlp-pot-provider HTTP
        //                      service (ECS sidecar). When set, the yt-dlp plugin
        //                      mints a fresh PoToken per call — bypasses
        //                      YouTube's IP scoring without a tracked session.
        String cookiesPath    = DotEnv.get("YT_DLP_COOKIES");
        String poToken        = DotEnv.get("YT_DLP_PO_TOKEN");
        String potProviderUrl = DotEnv.get("YT_DLP_POT_PROVIDER_URL");
        String ipv6Block      = DotEnv.get("IPV6_BLOCK");
        this.proxyUrl = validatedProxy(DotEnv.get("YT_DLP_PROXY"));
        this.source = new YoutubeSource(YoutubeSourceOptions.builder()
                .ytDlpPath(Binaries.YT_DLP)
                .ytDlpCookiesPath(cookiesPath == null ? "" : cookiesPath)
                .proxy(proxyUrl)
                .ytDlpPoToken(poToken == null ? "" : poToken)
                .ytDlpPotProviderUrl(potProviderUrl == null ? "" : potProviderUrl)
                .ipv6Block(ipv6Block == null ? "" : ipv6Block)
                .build());
    }

    /**
     * Accepts the proxy only if it is something every hop can actually use; a
     * half-applied proxy is worse than none (IP-bound stream URLs would 403).
     * On rejection the bot runs unproxied and says exactly what to fix.
     */
    private static String validatedProxy(String raw) {
        if (raw == null || raw.isBlank()) return "";
        Optional<ProxyUrl> parsed = ProxyUrl.parse(raw);
        if (parsed.isEmpty()) {
            System.err.println("[MusicService] YT_DLP_PROXY is set but unusable - expected "
                    + "http://user:pass@host:port with an explicit HTTP CONNECT port "
                    + "(Bright Data: port 33335; the SOCKS5 port 22228 rejects CDN targets). "
                    + "Running WITHOUT a proxy.");
            return "";
        }
        System.out.println("[MusicService] YouTube pipeline routed through proxy " + parsed.get());
        return raw.trim();
    }

    public YoutubeSource getSource() {
        return source;
    }

    public GuildAudioPlayer getOrCreate(Guild guild) {
        return players.computeIfAbsent(guild.getIdLong(), id -> {
            GuildAudioPlayer player = new GuildAudioPlayer(source::resolveLazy, proxyUrl);
            AudioManager manager = guild.getAudioManager();
            manager.setSendingHandler(new PcmAudioSendHandler(player));
            manager.setConnectionListener(new VoiceConnectionLogger(guild.getName()));
            return player;
        });
    }
}
