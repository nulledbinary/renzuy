package renzuy.commands.moderation;

import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses moderator-facing duration strings like {@code 90h}, {@code 60m},
 * {@code 120s}, {@code 7d}. Single-component only — {@code 1h30m} is rejected
 * to keep the surface predictable; you'd write {@code 90m} instead.
 *
 * <p>{@link #parseOrThrow(String)} returns a {@link Duration}; the upper bound
 * each command needs (Discord caps timeouts at 28 days) is enforced at the
 * call site.
 */
public final class DurationParser {

    private static final Pattern PATTERN = Pattern.compile("^(\\d+)\\s*([smhd])$");

    private DurationParser() {}

    public static final class InvalidDurationException extends RuntimeException {
        public InvalidDurationException(String message) { super(message); }
    }

    public static Duration parseOrThrow(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new InvalidDurationException(
                    "Duration is required. Use e.g. `30s`, `15m`, `2h`, `7d`.");
        }
        Matcher m = PATTERN.matcher(raw.strip().toLowerCase());
        if (!m.matches()) {
            throw new InvalidDurationException(
                    "Could not parse `" + raw + "`. Use a single value: e.g. `30s`, `15m`, `2h`, `7d`.");
        }
        long n;
        try {
            n = Long.parseLong(m.group(1));
        } catch (NumberFormatException e) {
            throw new InvalidDurationException("Duration is too large to parse.");
        }
        if (n <= 0) {
            throw new InvalidDurationException("Duration must be greater than zero.");
        }
        return switch (m.group(2)) {
            case "s" -> Duration.ofSeconds(n);
            case "m" -> Duration.ofMinutes(n);
            case "h" -> Duration.ofHours(n);
            case "d" -> Duration.ofDays(n);
            default  -> throw new InvalidDurationException("Unknown unit `" + m.group(2) + "`.");
        };
    }

    /** Renders a {@link Duration} as a compact human string used in reply confirmations. */
    public static String humanize(Duration d) {
        long s = d.getSeconds();
        if (s % 86400 == 0) return (s / 86400) + "d";
        if (s % 3600  == 0) return (s / 3600)  + "h";
        if (s % 60    == 0) return (s / 60)    + "m";
        return s + "s";
    }
}
