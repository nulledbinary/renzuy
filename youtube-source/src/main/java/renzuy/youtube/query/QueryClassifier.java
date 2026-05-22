package renzuy.youtube.query;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Turns a raw {@code /play} argument into a {@link Result} the resolver can act on,
 * doing <strong>zero network I/O</strong>. Classification is the first step of every
 * resolution, so it must be instant.
 */
public final class QueryClassifier {

    /** What kind of thing the user gave us. */
    public enum Kind {
        /** A single YouTube video — {@link Result#value()} is the 11-char video id. */
        VIDEO,
        /** Free-text search — {@link Result#value()} is the search term verbatim. */
        SEARCH,
        /** A YouTube playlist URL with no specific video — value is the playlist id. */
        PLAYLIST,
        /** A non-YouTube URL (Spotify, SoundCloud, a direct file, ...) — value is the URL. */
        FOREIGN_URL
    }

    /** The classification outcome. */
    public record Result(Kind kind, String value) {}

    private static final Set<String> YOUTUBE_HOSTS = Set.of(
            "youtube.com", "m.youtube.com", "music.youtube.com",
            "youtu.be", "youtube-nocookie.com");

    private static final String[] VIDEO_PATH_PREFIXES = {"/shorts/", "/embed/", "/live/", "/v/"};

    private static final Pattern VIDEO_ID = Pattern.compile("[A-Za-z0-9_-]{11}");
    private static final Pattern BARE_HOST = Pattern.compile("[A-Za-z0-9.-]+\\.[A-Za-z]{2,}(/.*)?");

    private QueryClassifier() {}

    /**
     * Classifies {@code raw}. Never returns {@code null}.
     *
     * @throws IllegalArgumentException if {@code raw} is null or blank
     */
    public static Result classify(String raw) {
        String input = raw == null ? "" : raw.strip();
        if (input.isEmpty()) {
            throw new IllegalArgumentException("Query is empty");
        }

        String url = asUrl(input);
        if (url == null) {
            return new Result(Kind.SEARCH, input);
        }

        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            return new Result(Kind.SEARCH, input);
        }
        String host = uri.getHost();
        if (host == null) {
            return new Result(Kind.SEARCH, input);
        }
        host = host.toLowerCase(Locale.ROOT);
        if (host.startsWith("www.")) {
            host = host.substring(4);
        }
        if (!YOUTUBE_HOSTS.contains(host)) {
            return new Result(Kind.FOREIGN_URL, url);
        }

        String path = uri.getPath() == null ? "" : uri.getPath();
        Map<String, String> params = parseQuery(uri.getRawQuery());

        // youtu.be/<id>
        if (host.equals("youtu.be")) {
            return videoOrForeign(stripSlashes(path), url);
        }
        // /watch?v=<id>  (a `list` param alongside `v` is ignored — play the video)
        if ((path.equals("/watch") || path.equals("/watch/")) && params.get("v") != null) {
            return videoOrForeign(params.get("v"), url);
        }
        // /shorts/<id>, /embed/<id>, /live/<id>, /v/<id>
        for (String prefix : VIDEO_PATH_PREFIXES) {
            if (path.startsWith(prefix)) {
                return videoOrForeign(stripSlashes(path.substring(prefix.length())), url);
            }
        }
        // A playlist URL with no specific video.
        String list = params.get("list");
        if (list != null && !list.isBlank()) {
            return new Result(Kind.PLAYLIST, list);
        }
        // A YouTube URL we do not specifically understand — let the fallback try it.
        return new Result(Kind.FOREIGN_URL, url);
    }

    /** @return a URL string if {@code input} looks like one, else {@code null} (it is a search). */
    private static String asUrl(String input) {
        // Any whitespace means it is a search phrase, not a URL.
        if (input.chars().anyMatch(Character::isWhitespace)) {
            return null;
        }
        String lower = input.toLowerCase(Locale.ROOT);
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            return input;
        }
        // Scheme-less but host-shaped, e.g. "youtube.com/watch?v=..." — assume https.
        if (BARE_HOST.matcher(input).matches()) {
            return "https://" + input;
        }
        return null;
    }

    private static Result videoOrForeign(String candidate, String originalUrl) {
        if (candidate != null && candidate.length() >= 11) {
            String id = candidate.substring(0, 11);
            if (VIDEO_ID.matcher(id).matches()) {
                return new Result(Kind.VIDEO, id);
            }
        }
        return new Result(Kind.FOREIGN_URL, originalUrl);
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> out = new HashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) {
            return out;
        }
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = pair.substring(0, eq);
            String value = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            out.putIfAbsent(key, value);
        }
        return out;
    }

    private static String stripSlashes(String s) {
        int start = 0;
        int end = s.length();
        while (start < end && s.charAt(start) == '/') {
            start++;
        }
        while (end > start && s.charAt(end - 1) == '/') {
            end--;
        }
        return s.substring(start, end);
    }
}
