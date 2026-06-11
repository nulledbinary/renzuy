package renzuy.logging;

import java.util.Locale;
import java.util.Optional;

/**
 * Every event family the bot can log, addressable from {@code /bind} by its
 * slug. Each category is bound to a channel independently, so e.g. nickname
 * changes and confessions can flow into different channels.
 */
public enum LogCategory {

    CONFESSION("confession", "Confession audit — who posted which confession"),
    MESSAGE_EDIT("message-edit", "Message edits (before / after)"),
    MESSAGE_DELETE("message-delete", "Deleted messages with cached content and attachments"),
    MEMBER_JOIN("member-join", "Members joining the server"),
    MEMBER_LEAVE("member-leave", "Members leaving or being removed"),
    NICKNAME("nickname", "Nickname changes"),
    TIMEOUT("timeout", "Timeouts applied / lifted"),
    BAN("ban", "Bans (with moderator and reason from the audit log)"),
    UNBAN("unban", "Unbans"),
    VOICE("voice", "Voice channel joins / leaves / moves");

    private final String slug;
    private final String description;

    LogCategory(String slug, String description) {
        this.slug = slug;
        this.description = description;
    }

    public String slug() {
        return slug;
    }

    public String description() {
        return description;
    }

    public static Optional<LogCategory> fromSlug(String raw) {
        if (raw == null) return Optional.empty();
        String wanted = raw.strip().toLowerCase(Locale.ROOT);
        for (LogCategory category : values()) {
            if (category.slug.equals(wanted)) {
                return Optional.of(category);
            }
        }
        return Optional.empty();
    }
}
