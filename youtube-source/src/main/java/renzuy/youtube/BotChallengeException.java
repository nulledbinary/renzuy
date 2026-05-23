package renzuy.youtube;

/**
 * Thrown when YouTube serves the "Sign in to confirm you're not a bot" wall in
 * response to a yt-dlp request — typically because the request egressed from a
 * datacenter IP without an authenticated cookie jar.
 *
 * <p>Distinct from a generic {@link YoutubeSourceException} so the UI layer can
 * render a stable, friendly message instead of leaking the multi-line yt-dlp
 * stderr to every user that runs {@code /play} while the wall is up.
 */
public final class BotChallengeException extends YoutubeSourceException {

    public BotChallengeException(String message) {
        super(message);
    }

    public BotChallengeException(String message, Throwable cause) {
        super(message, cause);
    }
}
