package renzuy.youtube;

/**
 * Thrown when a query cannot be turned into a playable {@link AudioReference}.
 *
 * <p>Unchecked on purpose: resolution runs on a dedicated worker thread in the bot,
 * and this is a terminal "tell the user it failed" condition, not something every
 * call site should be forced to declare.
 */
public class YoutubeSourceException extends RuntimeException {

    public YoutubeSourceException(String message) {
        super(message);
    }

    public YoutubeSourceException(String message, Throwable cause) {
        super(message, cause);
    }
}
