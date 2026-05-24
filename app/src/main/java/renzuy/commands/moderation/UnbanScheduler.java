package renzuy.commands.moderation;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import net.dv8tion.jda.api.entities.Guild;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * In-process scheduler for {@code /tempban} unbans.
 *
 * <p>This is intentionally in-memory: persisting would mean adding a database,
 * and a bot restart is already disruptive enough that operators expect to
 * re-issue moderation actions. If the bot restarts before a tempban expires,
 * the user remains banned until someone manually unbans them — that's the
 * documented behaviour, and the alternative (silently extending bans across
 * restarts) is worse.
 */
public final class UnbanScheduler {

    private static final Logger log = LoggerFactory.getLogger(UnbanScheduler.class);

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "unban-scheduler");
        t.setDaemon(true);
        return t;
    });

    public void schedule(Guild guild, String userId, Duration after, String reason) {
        long delayMillis = Math.max(0L, after.toMillis());
        String auditReason = reason == null ? "tempban expired" : reason;
        executor.schedule(() -> {
            try {
                guild.unban(net.dv8tion.jda.api.entities.UserSnowflake.fromId(userId))
                        .reason(auditReason)
                        .queue(
                                v   -> log.info("[tempban] auto-unbanned {} in guild {}", userId, guild.getId()),
                                err -> log.warn("[tempban] failed to auto-unban {} in guild {}: {}",
                                        userId, guild.getId(), err.getMessage()));
            } catch (RuntimeException e) {
                log.warn("[tempban] scheduler task threw for {} in guild {}: {}",
                        userId, guild.getId(), e.getMessage());
            }
        }, delayMillis, TimeUnit.MILLISECONDS);
    }

    public void shutdown() {
        executor.shutdownNow();
    }
}
