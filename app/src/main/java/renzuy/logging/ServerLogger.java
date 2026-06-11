package renzuy.logging;

import java.awt.Color;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.audit.ActionType;
import net.dv8tion.jda.api.audit.AuditLogEntry;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.Message.Attachment;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.events.guild.GuildBanEvent;
import net.dv8tion.jda.api.events.guild.GuildUnbanEvent;
import net.dv8tion.jda.api.events.guild.member.GuildMemberJoinEvent;
import net.dv8tion.jda.api.events.guild.member.GuildMemberRemoveEvent;
import net.dv8tion.jda.api.events.guild.member.update.GuildMemberUpdateNicknameEvent;
import net.dv8tion.jda.api.events.guild.member.update.GuildMemberUpdateTimeOutEvent;
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceUpdateEvent;
import net.dv8tion.jda.api.events.message.MessageDeleteEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.message.MessageUpdateEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.jetbrains.annotations.NotNull;

/**
 * Audit-style server logger.
 *
 * <p>Each event family is routed to the channel bound for its
 * {@link LogCategory} via {@code /bind} — there is no single log sink anymore,
 * and bindings persist across restarts through {@link BindStore}.
 *
 * <p>Embeds carry the full content snapshot: deleted messages surface the
 * original text + any attachment URLs (images, GIFs, files); edits show
 * before/after; member/voice/ban events render as rich embeds.
 *
 * <p>To support deletion logging the bot keeps a short-lived in-memory cache of
 * every message it sees (bounded LRU) — Discord's delete event does not carry
 * the original content, so we cache on receipt and look up on delete.
 */
public final class ServerLogger extends ListenerAdapter {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final Color C_CREATE = new Color(0x57F287);
    private static final Color C_UPDATE = new Color(0x5865F2);
    private static final Color C_DELETE = new Color(0xED4245);
    private static final Color C_MUTE   = new Color(0xFEE75C);
    private static final Color C_VOICE  = new Color(0x9B59B6);

    private static final int MESSAGE_CACHE_LIMIT = 5_000;

    private record CachedMessage(
            long authorId, String authorTag, String authorAvatar,
            long channelId, String content,
            List<String> attachmentUrls, List<String> stickerUrls,
            OffsetDateTime createdAt) {}

    private final BindStore binds;

    /** Bounded LRU of recent messages so we can render deletions with content. */
    private final Map<Long, CachedMessage> messageCache = Collections.synchronizedMap(
            new LinkedHashMap<>(1024, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, CachedMessage> eldest) {
                    return size() > MESSAGE_CACHE_LIMIT;
                }
            });

    public ServerLogger(BindStore binds) {
        this.binds = binds;
    }

    // ---------------- routing ----------------

    private void post(Guild guild, LogCategory category, MessageEmbed embed) {
        postMany(guild, category, List.of(embed));
    }

    private void postMany(Guild guild, LogCategory category, List<MessageEmbed> embeds) {
        if (embeds.isEmpty()) return;
        Long channelId = binds.channelFor(guild.getIdLong(), category);
        if (channelId == null) return;
        GuildMessageChannel channel = guild.getChannelById(GuildMessageChannel.class, channelId);
        if (channel == null || !guild.getSelfMember().hasAccess(channel)) return;
        // Discord allows up to 10 embeds per message.
        for (int i = 0; i < embeds.size(); i += 10) {
            channel.sendMessageEmbeds(embeds.subList(i, Math.min(i + 10, embeds.size())))
                    .queue(v -> {}, err -> {});
        }
    }

    private static MessageEmbed event(Color color, String title, String body) {
        return new EmbedBuilder()
                .setColor(color)
                .setAuthor(title)
                .setDescription(body)
                .setFooter(STAMP.format(OffsetDateTime.now()))
                .build();
    }

    // ---------------- message cache ----------------

    @Override
    public void onMessageReceived(@NotNull MessageReceivedEvent event) {
        if (!event.isFromGuild() || event.getAuthor().isBot()) return;
        cache(event.getMessage());
    }

    private void cache(Message message) {
        List<String> attachUrls = new ArrayList<>();
        for (Attachment a : message.getAttachments()) {
            attachUrls.add(a.getUrl());
        }
        List<String> stickerUrls = new ArrayList<>();
        message.getStickers().forEach(s -> stickerUrls.add(s.getIconUrl()));

        User author = message.getAuthor();
        messageCache.put(message.getIdLong(), new CachedMessage(
                author.getIdLong(),
                author.getAsTag(),
                author.getEffectiveAvatarUrl(),
                message.getChannel().getIdLong(),
                message.getContentRaw(),
                attachUrls,
                stickerUrls,
                message.getTimeCreated()));
    }

    // ---------------- chat events ----------------

    @Override
    public void onMessageUpdate(@NotNull MessageUpdateEvent event) {
        if (!event.isFromGuild() || event.getAuthor().isBot()) return;
        Message after = event.getMessage();
        CachedMessage before = messageCache.get(after.getIdLong());

        EmbedBuilder b = new EmbedBuilder()
                .setColor(C_UPDATE)
                .setAuthor("Message edited", null, event.getAuthor().getEffectiveAvatarUrl())
                .setDescription(event.getAuthor().getAsMention() + " edited a message in <#"
                        + event.getChannel().getId() + ">")
                .setFooter("Author: " + event.getAuthor().getAsTag()
                        + " · " + STAMP.format(OffsetDateTime.now()));

        if (before != null && !before.content().isBlank()) {
            b.addField("Before", truncate(before.content(), 900), false);
        }
        String now = after.getContentRaw();
        if (!now.isBlank()) {
            b.addField("After", truncate(now, 900), false);
        }
        b.addField("Jump", "[Open in channel](" + after.getJumpUrl() + ")", false);

        post(event.getGuild(), LogCategory.MESSAGE_EDIT, b.build());
        // Refresh cache so subsequent edits diff against the latest body.
        cache(after);
    }

    @Override
    public void onMessageDelete(@NotNull MessageDeleteEvent event) {
        if (!event.isFromGuild()) return;
        CachedMessage cached = messageCache.remove(event.getMessageIdLong());

        EmbedBuilder b = new EmbedBuilder()
                .setColor(C_DELETE)
                .setAuthor("Message deleted",
                        null,
                        cached != null ? cached.authorAvatar() : null)
                .setFooter("Message ID: " + event.getMessageId()
                        + " · " + STAMP.format(OffsetDateTime.now()));

        StringBuilder desc = new StringBuilder();
        desc.append("**Channel:** <#").append(event.getChannel().getId()).append('>');
        if (cached != null) {
            desc.append("\n**Author:** <@").append(cached.authorId()).append("> (")
                    .append(cached.authorTag()).append(")");
            desc.append("\n**Sent:** <t:").append(cached.createdAt().toEpochSecond()).append(":f>");
        } else {
            desc.append("\n_(content not cached — message predates the bot's current uptime)_");
        }
        b.setDescription(desc.toString());

        List<MessageEmbed> extra = new ArrayList<>();

        if (cached != null) {
            if (!cached.content().isBlank()) {
                b.addField("Content", truncate(cached.content(), 1000), false);
                String firstLink = firstUrl(cached.content());
                if (firstLink != null) {
                    b.addField("First link", firstLink, false);
                }
            }
            if (!cached.attachmentUrls().isEmpty()) {
                StringBuilder a = new StringBuilder();
                String firstImage = null;
                for (String url : cached.attachmentUrls()) {
                    a.append(url).append('\n');
                    if (firstImage == null && looksLikeImage(url)) firstImage = url;
                }
                b.addField("Attachments (" + cached.attachmentUrls().size() + ")",
                        truncate(a.toString(), 1000), false);
                if (firstImage != null) {
                    b.setImage(firstImage);
                }
                // One small extra embed per additional image so they're all visible.
                boolean skipFirst = firstImage != null;
                for (String url : cached.attachmentUrls()) {
                    if (skipFirst && url.equals(firstImage)) { skipFirst = false; continue; }
                    if (looksLikeImage(url)) {
                        extra.add(new EmbedBuilder()
                                .setColor(C_DELETE)
                                .setImage(url)
                                .build());
                    }
                }
            }
            if (!cached.stickerUrls().isEmpty()) {
                b.addField("Stickers",
                        String.join("\n", cached.stickerUrls()), false);
                if (b.build().getImage() == null) {
                    b.setImage(cached.stickerUrls().get(0));
                }
            }
        }

        List<MessageEmbed> all = new ArrayList<>();
        all.add(b.build());
        all.addAll(extra);
        postMany(event.getGuild(), LogCategory.MESSAGE_DELETE, all);
    }

    // ---------------- member events ----------------

    @Override
    public void onGuildMemberJoin(@NotNull GuildMemberJoinEvent event) {
        Member m = event.getMember();
        long ageDays = (System.currentTimeMillis() - m.getUser().getTimeCreated().toInstant().toEpochMilli())
                / (1000L * 60 * 60 * 24);
        MessageEmbed embed = new EmbedBuilder()
                .setColor(C_CREATE)
                .setAuthor("Member joined", null, m.getUser().getEffectiveAvatarUrl())
                .setThumbnail(m.getUser().getEffectiveAvatarUrl())
                .setDescription(m.getAsMention() + " joined the server.")
                .addField("Tag", m.getUser().getAsTag(), true)
                .addField("ID", m.getId(), true)
                .addField("Account age", ageDays + " days", true)
                .setFooter(STAMP.format(OffsetDateTime.now()))
                .build();
        post(event.getGuild(), LogCategory.MEMBER_JOIN, embed);
    }

    @Override
    public void onGuildMemberRemove(@NotNull GuildMemberRemoveEvent event) {
        User u = event.getUser();
        MessageEmbed embed = new EmbedBuilder()
                .setColor(C_DELETE)
                .setAuthor("Member left", null, u.getEffectiveAvatarUrl())
                .setThumbnail(u.getEffectiveAvatarUrl())
                .setDescription("**" + u.getAsTag() + "** (" + u.getId() + ") left or was removed.")
                .setFooter(STAMP.format(OffsetDateTime.now()))
                .build();
        post(event.getGuild(), LogCategory.MEMBER_LEAVE, embed);
    }

    @Override
    public void onGuildMemberUpdateNickname(@NotNull GuildMemberUpdateNicknameEvent event) {
        String before = event.getOldNickname() == null ? event.getUser().getName() : event.getOldNickname();
        String after  = event.getNewNickname() == null ? event.getUser().getName() : event.getNewNickname();
        MessageEmbed embed = new EmbedBuilder()
                .setColor(C_UPDATE)
                .setAuthor("Nickname changed", null, event.getUser().getEffectiveAvatarUrl())
                .setDescription(event.getUser().getAsMention() + " changed nickname.")
                .addField("Before", before, true)
                .addField("After", after, true)
                .setFooter(STAMP.format(OffsetDateTime.now()))
                .build();
        post(event.getGuild(), LogCategory.NICKNAME, embed);
    }

    @Override
    public void onGuildMemberUpdateTimeOut(@NotNull GuildMemberUpdateTimeOutEvent event) {
        OffsetDateTime until = event.getNewTimeOutEnd();
        String body = until == null
                ? event.getMember().getAsMention() + " timeout ended."
                : event.getMember().getAsMention() + " timed out until <t:" + until.toEpochSecond() + ":R>.";
        post(event.getGuild(), LogCategory.TIMEOUT, event(C_MUTE, "Timeout updated", body));
    }

    // ---------------- ban events ----------------

    @Override
    public void onGuildBan(@NotNull GuildBanEvent event) {
        Guild guild = event.getGuild();
        guild.retrieveAuditLogs().type(ActionType.BAN).limit(1).queue(entries -> {
            String moderator = entries.stream().findFirst()
                    .map(AuditLogEntry::getUser)
                    .map(u -> u == null ? "unknown" : u.getAsMention())
                    .orElse("unknown");
            String reason = entries.stream().findFirst()
                    .map(AuditLogEntry::getReason)
                    .orElse(null);
            String body = "**" + event.getUser().getAsTag() + "** (" + event.getUser().getId()
                    + ") was banned.\nBy: " + moderator
                    + (reason == null ? "" : "\nReason: " + reason);
            post(guild, LogCategory.BAN, event(C_DELETE, "User banned", body));
        }, err -> {
            String body = "**" + event.getUser().getAsTag() + "** (" + event.getUser().getId() + ") was banned.";
            post(guild, LogCategory.BAN, event(C_DELETE, "User banned", body));
        });
    }

    @Override
    public void onGuildUnban(@NotNull GuildUnbanEvent event) {
        String body = "**" + event.getUser().getAsTag() + "** (" + event.getUser().getId() + ") was unbanned.";
        post(event.getGuild(), LogCategory.UNBAN, event(C_CREATE, "User unbanned", body));
    }

    // ---------------- voice events ----------------

    @Override
    public void onGuildVoiceUpdate(@NotNull GuildVoiceUpdateEvent event) {
        if (event.getMember().getUser().isBot()) return;
        String left  = event.getChannelLeft()  == null ? null : event.getChannelLeft().getName();
        String join  = event.getChannelJoined() == null ? null : event.getChannelJoined().getName();
        String body;
        if (left != null && join != null) {
            body = event.getMember().getAsMention() + " moved from **" + left + "** to **" + join + "**.";
        } else if (join != null) {
            body = event.getMember().getAsMention() + " joined voice **" + join + "**.";
        } else if (left != null) {
            body = event.getMember().getAsMention() + " left voice **" + left + "**.";
        } else {
            return;
        }
        post(event.getGuild(), LogCategory.VOICE, event(C_VOICE, "Voice update", body));
    }

    // ---------------- utilities ----------------

    private static String truncate(String s, int max) {
        if (s.length() <= max) return s;
        return s.substring(0, max - 1) + "…";
    }

    private static boolean looksLikeImage(String url) {
        String lower = url.toLowerCase();
        int q = lower.indexOf('?');
        if (q >= 0) lower = lower.substring(0, q);
        return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".gif") || lower.endsWith(".webp") || lower.endsWith(".apng");
    }

    private static String firstUrl(String text) {
        if (text == null) return null;
        int idx = text.indexOf("http");
        if (idx < 0) return null;
        int end = idx;
        while (end < text.length() && !Character.isWhitespace(text.charAt(end))) end++;
        String candidate = text.substring(idx, end);
        return (candidate.startsWith("http://") || candidate.startsWith("https://")) ? candidate : null;
    }
}
