package renzuy.moderation;

import java.awt.Color;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.jetbrains.annotations.NotNull;

/**
 * Anti-raid / anti-nuke listener.
 *
 * <p>Tracks a sliding window of recent messages per (guild, user). When the
 * same user sends the same content (case-insensitive, whitespace-collapsed)
 * more than {@link #DUPLICATE_THRESHOLD} times within
 * {@link #WINDOW_SECONDS}, the bot purges those messages and times the user
 * out. The check fires on every message but only does work when the window
 * actually contains entries.
 *
 * <p>Also detects high-rate generic flooding ({@link #FLOOD_THRESHOLD}
 * messages within {@link #WINDOW_SECONDS}) regardless of content equality.
 */
public final class RaidGuard extends ListenerAdapter {

    private static final int  DUPLICATE_THRESHOLD = 4;
    private static final int  FLOOD_THRESHOLD     = 8;
    private static final long WINDOW_SECONDS      = 10L;
    private static final Duration TIMEOUT_DURATION = Duration.ofMinutes(10);

    private record Entry(long messageId, long channelId, String fingerprint, Instant at) {}

    private final Map<Long, Deque<Entry>> history = new ConcurrentHashMap<>();

    @Override
    public void onMessageReceived(@NotNull MessageReceivedEvent event) {
        if (!event.isFromGuild() || event.getAuthor().isBot()) return;
        Member member = event.getMember();
        if (member == null) return;
        // Don't fight against moderators or admins — they may be doing housekeeping.
        if (member.hasPermission(Permission.MODERATE_MEMBERS, Permission.MESSAGE_MANAGE)
                || member.hasPermission(Permission.ADMINISTRATOR)) return;

        Message message = event.getMessage();
        long key = compositeKey(event.getGuild().getIdLong(), event.getAuthor().getIdLong());
        Deque<Entry> queue = history.computeIfAbsent(key, k -> new ArrayDeque<>());
        Instant now = Instant.now();

        synchronized (queue) {
            // Drop expired entries.
            Instant cutoff = now.minusSeconds(WINDOW_SECONDS);
            while (!queue.isEmpty() && queue.peekFirst().at().isBefore(cutoff)) {
                queue.pollFirst();
            }
            String fp = fingerprint(message);
            queue.addLast(new Entry(message.getIdLong(), message.getChannel().getIdLong(), fp, now));

            int duplicates = 0;
            for (Entry e : queue) {
                if (e.fingerprint().equals(fp)) duplicates++;
            }
            int total = queue.size();

            if (duplicates >= DUPLICATE_THRESHOLD || total >= FLOOD_THRESHOLD) {
                Set<Long> messageIds = new HashSet<>();
                for (Entry e : queue) messageIds.add(e.messageId());
                queue.clear();
                punish(event.getGuild(), member, message,
                        duplicates >= DUPLICATE_THRESHOLD
                                ? "duplicate-message flood (" + duplicates + "× in " + WINDOW_SECONDS + "s)"
                                : "message rate flood (" + total + " in " + WINDOW_SECONDS + "s)",
                        messageIds);
            }
        }
    }

    private static void punish(Guild guild, Member member, Message trigger, String reason, Set<Long> messageIds) {
        // Delete the trigger and any same-author messages still visible in the
        // current channel — sweeping across channels is best-effort via cache.
        for (long id : messageIds) {
            trigger.getChannel().deleteMessageById(id).queue(v -> {}, e -> {});
        }
        if (guild.getSelfMember().canInteract(member)) {
            member.timeoutFor(TIMEOUT_DURATION).reason("RaidGuard: " + reason).queue(v -> {}, e -> {});
        }
        EmbedBuilder b = new EmbedBuilder()
                .setColor(new Color(0xED4245))
                .setAuthor("Raid-guard triggered", null, member.getUser().getEffectiveAvatarUrl())
                .setDescription(member.getAsMention() + " was timed out for **"
                        + reason + "**.")
                .setFooter("Auto-action by RaidGuard");
        trigger.getChannel().sendMessageEmbeds(b.build()).queue(v -> {}, e -> {});
    }

    private static String fingerprint(Message message) {
        StringBuilder sb = new StringBuilder();
        String content = message.getContentRaw();
        if (content != null) {
            // Lowercase + collapse whitespace; that's enough to catch the "same
            // copy-pasted message" raid pattern without falsely matching short
            // common replies (e.g. "ok") because the flood-rate trigger handles those.
            sb.append(content.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip());
        }
        // Attachments-only spam should also count as duplicates if the same URL repeats.
        message.getAttachments().forEach(a -> sb.append('|').append(a.getUrl()));
        return sb.toString();
    }

    private static long compositeKey(long guildId, long userId) {
        return (guildId * 31L) ^ userId;
    }
}
