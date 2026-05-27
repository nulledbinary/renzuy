package renzuy.commands;

import java.awt.Color;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.Message.Attachment;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import org.jetbrains.annotations.NotNull;
import renzuy.commands.text.TextCommand;
import renzuy.ui.Embeds;

/**
 * {@code /afk [reason]} and {@code <prefix>afk [reason]}: marks the caller AFK.
 *
 * <p>Reason may include a link to an image or GIF; bot extracts the first
 * image URL and renders it inline in the AFK embed. Plain text or other links
 * are kept in the reason body. Discord attachments (when invoked by prefix)
 * are honoured only if the file's content-type is image-y.
 *
 * <p>When another user pings an AFK user, the bot replies with the AFK embed
 * and a "Duration" rendered via Discord's relative timestamp
 * (<code>&lt;t:…:R&gt;</code>) — clients tick the timestamp client-side, giving
 * a live "5 minutes ago" → "6 minutes ago" feel without us editing messages.
 *
 * <p>When the AFK user themselves posts a message, the AFK state is cleared
 * and the bot replies once acknowledging their return.
 */
public final class AfkCommand extends ListenerAdapter implements TextCommand {

    public static final String NAME = "afk";
    public static final String REASON_OPTION = "reason";

    private static final Color AFK_COLOR = new Color(0xFAA61A);
    private static final int MAX_REASON_LEN = 400;

    private record AfkEntry(Instant since, String reason, String imageUrl) {}

    private final Map<Long, AfkEntry> afkByUser = new ConcurrentHashMap<>();

    // ---------------- /afk ----------------

    @Override
    public void onSlashCommandInteraction(@NotNull SlashCommandInteractionEvent event) {
        if (!event.getName().equals(NAME)) return;
        if (event.getGuild() == null) {
            event.replyEmbeds(Embeds.warn("This command can only be used in a server."))
                    .setEphemeral(true).queue();
            return;
        }
        OptionMapping reasonOpt = event.getOption(REASON_OPTION);
        String reason = reasonOpt == null ? "" : reasonOpt.getAsString().strip();
        String image = extractImageUrl(reason);
        if (image != null) {
            reason = stripUrl(reason, image).strip();
        }
        if (reason.length() > MAX_REASON_LEN) {
            reason = reason.substring(0, MAX_REASON_LEN) + "…";
        }
        AfkEntry entry = new AfkEntry(Instant.now(), reason, image);
        afkByUser.put(event.getUser().getIdLong(), entry);

        EmbedBuilder b = new EmbedBuilder()
                .setColor(AFK_COLOR)
                .setAuthor(event.getUser().getName() + " is now AFK", null,
                        event.getUser().getEffectiveAvatarUrl())
                .setDescription(reason.isBlank() ? "_No reason given._" : "**Reason:** _" + escape(reason) + "_")
                .setFooter("You'll be marked back when you next send a message.");
        if (image != null) {
            b.setImage(image);
        }
        event.replyEmbeds(b.build()).setEphemeral(true).queue();
    }

    @Override
    public String name() { return NAME; }

    @Override
    public void execute(MessageReceivedEvent event, String args) {
        String reason = args == null ? "" : args.strip();
        String image = extractImageUrl(reason);
        if (image != null) {
            reason = stripUrl(reason, image).strip();
        }
        // Prefix invocations can attach files; honour image attachments.
        if (image == null) {
            for (Attachment a : event.getMessage().getAttachments()) {
                if (a.isImage() || looksLikeImageContentType(a.getContentType())) {
                    image = a.getUrl();
                    break;
                }
            }
        }
        if (reason.length() > MAX_REASON_LEN) {
            reason = reason.substring(0, MAX_REASON_LEN) + "…";
        }
        afkByUser.put(event.getAuthor().getIdLong(), new AfkEntry(Instant.now(), reason, image));

        EmbedBuilder b = new EmbedBuilder()
                .setColor(AFK_COLOR)
                .setAuthor(event.getAuthor().getName() + " is now AFK", null,
                        event.getAuthor().getEffectiveAvatarUrl())
                .setDescription(reason.isBlank() ? "_No reason given._" : "**Reason:** _" + escape(reason) + "_")
                .setFooter("Send any message to clear your AFK.");
        if (image != null) {
            b.setImage(image);
        }
        event.getChannel().sendMessageEmbeds(b.build()).queue();
    }

    // ---------------- listener: ping target + return clearer ----------------

    @Override
    public void onMessageReceived(@NotNull MessageReceivedEvent event) {
        if (!event.isFromGuild() || event.getAuthor().isBot()) return;
        Message message = event.getMessage();

        AfkEntry self = afkByUser.remove(event.getAuthor().getIdLong());
        if (self != null) {
            long seconds = (System.currentTimeMillis() / 1000L) - self.since().getEpochSecond();
            event.getChannel().sendMessage("Welcome back " + event.getAuthor().getAsMention()
                            + " — removed your AFK. You were away for " + humanize(seconds) + ".")
                    .queue();
            // Continue: maybe author also pinged someone AFK; fall through.
        }

        List<User> mentioned = message.getMentions().getUsersBag().stream()
                .distinct()
                .filter(u -> !u.isBot())
                .filter(u -> !u.getId().equals(event.getAuthor().getId()))
                .toList();
        if (mentioned.isEmpty()) return;

        for (User target : mentioned) {
            AfkEntry entry = afkByUser.get(target.getIdLong());
            if (entry == null) continue;

            EmbedBuilder b = new EmbedBuilder()
                    .setColor(AFK_COLOR)
                    .setAuthor(target.getName() + " is AFK", null, target.getEffectiveAvatarUrl())
                    .setDescription("**Reason:** _" + (entry.reason().isBlank()
                            ? "no reason given" : escape(entry.reason())) + "_")
                    .addField("Duration",
                            "_since <t:" + entry.since().getEpochSecond() + ":R>_", false);
            if (entry.imageUrl() != null) {
                b.setImage(entry.imageUrl());
            }
            message.replyEmbeds(b.build()).mentionRepliedUser(false).queue();
            // Only one AFK notification per message, even if multiple AFK users were pinged.
            break;
        }
    }

    // ---------------- helpers ----------------

    private static String extractImageUrl(String text) {
        if (text == null) return null;
        int from = 0;
        while ((from = text.indexOf("http", from)) >= 0) {
            int end = from;
            while (end < text.length() && !Character.isWhitespace(text.charAt(end))) end++;
            String candidate = text.substring(from, end);
            String trimmed = candidate;
            int q = trimmed.indexOf('?');
            if (q >= 0) trimmed = trimmed.substring(0, q);
            String lower = trimmed.toLowerCase();
            if ((candidate.startsWith("http://") || candidate.startsWith("https://"))
                    && (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                    || lower.endsWith(".gif") || lower.endsWith(".webp") || lower.endsWith(".apng"))) {
                return candidate;
            }
            from = end;
        }
        // Special-case tenor / giphy / discord cdn — accept as image even without an extension.
        int idx = text.indexOf("http");
        if (idx >= 0) {
            int end = idx;
            while (end < text.length() && !Character.isWhitespace(text.charAt(end))) end++;
            String url = text.substring(idx, end);
            String low = url.toLowerCase();
            if (low.contains("tenor.com/view/") || low.contains("giphy.com")
                    || low.contains("cdn.discordapp.com/attachments")
                    || low.contains("media.discordapp.net")) {
                return url;
            }
        }
        return null;
    }

    private static String stripUrl(String text, String url) {
        int idx = text.indexOf(url);
        if (idx < 0) return text;
        return (text.substring(0, idx) + text.substring(idx + url.length())).strip();
    }

    private static boolean looksLikeImageContentType(String ct) {
        return ct != null && ct.toLowerCase().startsWith("image/");
    }

    private static String humanize(long seconds) {
        if (seconds < 60)      return seconds + "s";
        if (seconds < 3_600)   return (seconds / 60) + "m " + (seconds % 60) + "s";
        if (seconds < 86_400)  return (seconds / 3_600) + "h " + ((seconds % 3_600) / 60) + "m";
        return (seconds / 86_400) + "d " + ((seconds % 86_400) / 3_600) + "h";
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("*", "\\*")
                .replace("_", "\\_")
                .replace("`", "\\`")
                .replace("~", "\\~");
    }
}
