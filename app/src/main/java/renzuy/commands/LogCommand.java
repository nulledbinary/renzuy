package renzuy.commands;

import java.awt.Color;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.audit.ActionType;
import net.dv8tion.jda.api.audit.AuditLogEntry;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.events.guild.GuildBanEvent;
import net.dv8tion.jda.api.events.guild.GuildUnbanEvent;
import net.dv8tion.jda.api.events.guild.member.GuildMemberJoinEvent;
import net.dv8tion.jda.api.events.guild.member.GuildMemberRemoveEvent;
import net.dv8tion.jda.api.events.guild.member.update.GuildMemberUpdateNicknameEvent;
import net.dv8tion.jda.api.events.guild.member.update.GuildMemberUpdateTimeOutEvent;
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceUpdateEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageDeleteEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.message.MessageUpdateEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.jetbrains.annotations.NotNull;
import renzuy.ui.Embeds;

/**
 * Audit-style server logger.
 *
 * <p>Two responsibilities, on purpose, in one class to keep the channel-binding
 * state local: (1) the {@code /log} slash command, which binds the invoking
 * channel as the log sink for that guild; (2) a JDA listener that watches
 * everything interesting and posts compact embeds to whichever channel is
 * currently bound.
 *
 * <p>State is in-memory only — restart wipes bindings. Persistence here would
 * require schema, migrations, and a deploy story; the operator typing
 * {@code /log} on restart is cheaper than all of that.
 */
public final class LogCommand extends ListenerAdapter {

    public static final String NAME = "log";

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final Color C_CREATE = new Color(0x57F287);
    private static final Color C_UPDATE = new Color(0x5865F2);
    private static final Color C_DELETE = new Color(0xED4245);
    private static final Color C_MUTE   = new Color(0xFEE75C);
    private static final Color C_VOICE  = new Color(0x9B59B6);

    /** Guild ID → message-channel ID. Concurrent because events come in on JDA's pool. */
    private final Map<Long, Long> logChannelByGuild = new ConcurrentHashMap<>();

    // ---------------- /log slash entry ----------------

    @Override
    public void onSlashCommandInteraction(@NotNull SlashCommandInteractionEvent event) {
        if (!event.getName().equals(NAME)) return;

        Guild guild = event.getGuild();
        if (guild == null) {
            event.replyEmbeds(Embeds.warn("This command can only be used in a server."))
                    .setEphemeral(true).queue();
            return;
        }
        Member member = event.getMember();
        if (!Capability.VIEW_LOGS.grantedTo(member)) {
            event.replyEmbeds(Embeds.warn("You need **View Audit Log** or **Manage Server** to use `/log`."))
                    .setEphemeral(true).queue();
            return;
        }
        if (!event.getChannel().getType().isMessage()) {
            event.replyEmbeds(Embeds.error("Run `/log` in a normal text channel — that's where events will be posted."))
                    .setEphemeral(true).queue();
            return;
        }
        long channelId = event.getChannelIdLong();
        Long previous = logChannelByGuild.put(guild.getIdLong(), channelId);
        String body = previous == null
                ? "**Logging enabled here.** Server events will be posted to this channel."
                : previous == channelId
                        ? "This channel is already the log sink."
                        : "Moved logging here from <#" + previous + ">.";
        event.replyEmbeds(Embeds.info(body)).setEphemeral(true).queue();
    }

    // ---------------- helpers ----------------

    private void post(Guild guild, MessageEmbed embed) {
        Long channelId = logChannelByGuild.get(guild.getIdLong());
        if (channelId == null) return;
        GuildMessageChannel channel = guild.getChannelById(GuildMessageChannel.class, channelId);
        if (channel == null) {
            // Channel was deleted; drop the binding silently rather than spamming retries.
            logChannelByGuild.remove(guild.getIdLong(), channelId);
            return;
        }
        if (!guild.getSelfMember().hasAccess(channel)) return;
        channel.sendMessageEmbeds(embed).queue(v -> {}, err -> {});
    }

    private static MessageEmbed event(Color color, String title, String body) {
        return new EmbedBuilder()
                .setColor(color)
                .setAuthor(title)
                .setDescription(body)
                .setFooter(STAMP.format(OffsetDateTime.now()))
                .build();
    }

    // ---------------- chat events ----------------

    @Override
    public void onMessageReceived(@NotNull MessageReceivedEvent event) {
        if (!event.isFromGuild() || event.getAuthor().isBot()) return;
        // Skip the noisy default — only log chat at debug-style level: count by
        // surfacing edits and deletes, not every benign message. (Keeps the
        // channel readable; an admin who wants every line can read history.)
    }

    @Override
    public void onMessageUpdate(@NotNull MessageUpdateEvent event) {
        if (!event.isFromGuild() || event.getAuthor().isBot()) return;
        String content = event.getMessage().getContentDisplay();
        if (content.isBlank()) return;
        String body = "**Message edited** by " + event.getAuthor().getAsMention()
                + " in <#" + event.getChannel().getId() + ">\n"
                + "```\n" + truncate(content, 800) + "\n```";
        post(event.getGuild(), event(C_UPDATE, "Message edited", body));
    }

    @Override
    public void onMessageDelete(@NotNull MessageDeleteEvent event) {
        if (!event.isFromGuild()) return;
        String body = "**Message deleted** in <#" + event.getChannel().getId() + ">\n"
                + "Message ID: `" + event.getMessageId() + "`";
        post(event.getGuild(), event(C_DELETE, "Message deleted", body));
    }

    // ---------------- member events ----------------

    @Override
    public void onGuildMemberJoin(@NotNull GuildMemberJoinEvent event) {
        String body = event.getMember().getAsMention() + " joined the server.";
        post(event.getGuild(), event(C_CREATE, "Member joined", body));
    }

    @Override
    public void onGuildMemberRemove(@NotNull GuildMemberRemoveEvent event) {
        String body = "**" + event.getUser().getName() + "** (`" + event.getUser().getId()
                + "`) left or was removed.";
        post(event.getGuild(), event(C_DELETE, "Member left", body));
    }

    @Override
    public void onGuildMemberUpdateNickname(@NotNull GuildMemberUpdateNicknameEvent event) {
        String before = event.getOldNickname() == null ? event.getUser().getName() : event.getOldNickname();
        String after  = event.getNewNickname() == null ? event.getUser().getName() : event.getNewNickname();
        String body = event.getUser().getAsMention() + " nickname: `" + before + "` → `" + after + "`";
        post(event.getGuild(), event(C_UPDATE, "Nickname changed", body));
    }

    @Override
    public void onGuildMemberUpdateTimeOut(@NotNull GuildMemberUpdateTimeOutEvent event) {
        OffsetDateTime until = event.getNewTimeOutEnd();
        String body = until == null
                ? event.getMember().getAsMention() + " timeout ended."
                : event.getMember().getAsMention() + " timed out until <t:" + until.toEpochSecond() + ":R>.";
        post(event.getGuild(), event(C_MUTE, "Timeout updated", body));
    }

    // ---------------- ban events ----------------

    @Override
    public void onGuildBan(@NotNull GuildBanEvent event) {
        Guild guild = event.getGuild();
        event.getUser().getJDA();
        guild.retrieveAuditLogs().type(ActionType.BAN).limit(1).queue(entries -> {
            String moderator = entries.stream().findFirst()
                    .map(AuditLogEntry::getUser)
                    .map(u -> u == null ? "unknown" : u.getAsMention())
                    .orElse("unknown");
            String reason = entries.stream().findFirst()
                    .map(AuditLogEntry::getReason)
                    .orElse(null);
            String body = "**" + event.getUser().getName() + "** (`" + event.getUser().getId()
                    + "`) was banned.\nBy: " + moderator
                    + (reason == null ? "" : "\nReason: " + reason);
            post(guild, event(C_DELETE, "User banned", body));
        }, err -> {
            String body = "**" + event.getUser().getName() + "** (`" + event.getUser().getId() + "`) was banned.";
            post(guild, event(C_DELETE, "User banned", body));
        });
    }

    @Override
    public void onGuildUnban(@NotNull GuildUnbanEvent event) {
        String body = "**" + event.getUser().getName() + "** (`" + event.getUser().getId() + "`) was unbanned.";
        post(event.getGuild(), event(C_CREATE, "User unbanned", body));
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
        post(event.getGuild(), event(C_VOICE, "Voice update", body));
    }

    private static String truncate(String s, int max) {
        if (s.length() <= max) return s;
        return s.substring(0, max - 1) + "…";
    }

    // ---------------- text-command path: bind this channel ----------------

    /**
     * Optional text invocation: {@code <prefix>log} also binds the invoking
     * channel as the log sink. Same permission gate as the slash version.
     */
    public void handleText(MessageReceivedEvent event) {
        Member member = event.getMember();
        if (!Capability.VIEW_LOGS.grantedTo(member)) {
            event.getMessage().reply("You need **View Audit Log** or **Manage Server** to use `log`.")
                    .mentionRepliedUser(false).queue();
            return;
        }
        long channelId = event.getChannel().getIdLong();
        logChannelByGuild.put(event.getGuild().getIdLong(), channelId);
        event.getMessage().reply("Logging enabled here. Server events will be posted to this channel.")
                .mentionRepliedUser(false).queue();
    }
}
