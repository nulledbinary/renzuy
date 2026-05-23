package renzuy.youtube.innertube;

import java.util.List;
import java.util.Map;

/**
 * The Innertube client roster and rotation order.
 *
 * <p><strong>This is the file to edit when YouTube breaks in-process extraction.</strong>
 * YouTube changes its anti-bot measures regularly; when the Innertube fast path
 * starts failing, the fix is almost always one of:
 * <ul>
 *   <li>bump a {@code clientVersion} (and the matching User-Agent) to a current value;</li>
 *   <li>reorder {@link #PLAYER_ROTATION} so a still-working client is tried first;</li>
 *   <li>drop a client that has started returning ciphered URLs or demanding a PoToken.</li>
 * </ul>
 * Until then the bot keeps playing audio regardless: the yt-dlp fallback covers
 * everything the clients below cannot.
 *
 * <p>The player rotation deliberately contains only clients that have historically
 * returned <em>direct</em> (non-ciphered) stream URLs without a PoToken — this
 * library never executes YouTube's player JavaScript to decipher signatures.
 */
public final class InnertubeClients {

    private InnertubeClients() {}

    /**
     * The Oculus / Meta Quest YouTube app. Long-standing favourite for extraction:
     * returns direct stream URLs and has not required a PoToken.
     */
    public static final InnertubeClient ANDROID_VR = new InnertubeClient(
            "com.google.android.apps.youtube.vr.oculus/1.62.27 "
                    + "(Linux; U; Android 12; en_US; Quest 3; Build/SQ3A.220605.009.A1) gzip",
            28,
            Map.of(
                    "clientName", "ANDROID_VR",
                    "clientVersion", "1.62.27",
                    "deviceMake", "Oculus",
                    "deviceModel", "Quest 3",
                    "osName", "Android",
                    "osVersion", "12",
                    "androidSdkVersion", 32,
                    "hl", "en",
                    "gl", "US"));

    /**
     * The Apple iOS YouTube app. Kept defined but <strong>not in
     * {@link #PLAYER_ROTATION}</strong>: in practice its URLs (which carry
     * {@code c=IOS}) frequently 403 in ffmpeg even after a Range probe passes,
     * because googlevideo fingerprints the request more strictly than a Range
     * preflight reveals. Until we have a probe that matches ffmpeg's real GET,
     * IOS is unsafe to rely on for the player path.
     */
    public static final InnertubeClient IOS = new InnertubeClient(
            "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X; en_US)",
            5,
            Map.of(
                    "clientName", "IOS",
                    "clientVersion", "20.10.4",
                    "deviceMake", "Apple",
                    "deviceModel", "iPhone16,2",
                    "osName", "iPhone",
                    "osVersion", "18.3.2.22D82",
                    "hl", "en",
                    "gl", "US"));

    /**
     * The desktop web client. Its <em>player</em> formats are signature-ciphered, so
     * it is <strong>not</strong> in {@link #PLAYER_ROTATION}; it is used only for the
     * search endpoint, where no stream URLs are involved.
     */
    public static final InnertubeClient WEB = new InnertubeClient(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36",
            1,
            Map.of(
                    "clientName", "WEB",
                    "clientVersion", "2.20250310.01.00",
                    "hl", "en",
                    "gl", "US"));

    /**
     * Player clients tried in order. The first one that yields a direct-URL audio
     * format wins; on failure the next is tried; if all fail the caller falls back
     * to yt-dlp.
     */
    public static final List<InnertubeClient> PLAYER_ROTATION = List.of(ANDROID_VR);

    /** The client used for the search endpoint. */
    public static final InnertubeClient SEARCH_CLIENT = WEB;
}
