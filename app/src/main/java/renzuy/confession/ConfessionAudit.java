package renzuy.confession;

import java.awt.Color;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.attachmentupload.AttachmentUpload;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.modals.ModalMapping;
import net.dv8tion.jda.api.modals.Modal;
import net.dv8tion.jda.api.utils.FileUpload;
import org.jetbrains.annotations.NotNull;
import renzuy.commands.Capability;
import renzuy.logging.BindStore;
import renzuy.logging.LogCategory;
import renzuy.store.StateStore;
import renzuy.ui.Embeds;

/**
 * Moderation-facing side of {@code /confess}.
 *
 * <p>Confessions stay anonymous in chat, but every post is recorded in the
 * {@link StateStore} (post ID → author) and mirrored as an audit embed into
 * the channel bound to {@link LogCategory#CONFESSION}. The embed identifies
 * the author <b>by ID only</b> and carries two moderation buttons:
 *
 * <ul>
 *   <li><b>Restrict user</b> — toggles a persistent block that stops the
 *       author from using {@code /confess} again (and lifts it on a second
 *       press).</li>
 *   <li><b>Warn user</b> — opens a modal asking for a reason plus an optional
 *       PNG attachment, then DMs the warning to the author and logs the action
 *       back into the confession log channel.</li>
 * </ul>
 *
 * <p>Both the audit records and the restriction list persist, so the buttons
 * keep working after a restart or redeploy.
 */
public final class ConfessionAudit extends ListenerAdapter {

    private static final String BTN_RESTRICT = "confess-audit:restrict:";
    private static final String BTN_WARN     = "confess-audit:warn:";
    private static final String MODAL_WARN   = "confess-audit:warnmodal:";
    private static final String REASON_INPUT = "confess-audit:reason";
    private static final String IMAGE_INPUT  = "confess-audit:image";

    private static final Color C_AUDIT = new Color(0xE67E22);
    private static final Color C_WARN  = new Color(0xFEE75C);
    private static final int MAX_REASON_LEN = 500;
    /** Re-uploaded evidence is buffered in memory — refuse anything above Discord's free-tier cap. */
    private static final long MAX_IMAGE_BYTES = 8L * 1024 * 1024;

    private record Record(long guildId, long channelId, long authorId, long postedAt) {}

    private final StateStore store;
    private final BindStore binds;

    public ConfessionAudit(StateStore store, BindStore binds) {
        this.store = store;
        this.binds = binds;
    }

    // ---------------- recording ----------------

    /** True when the user has been restricted from {@code /confess} by a moderator. */
    public boolean isRestricted(long guildId, long userId) {
        return store.get(guildPk(guildId), "confess-block#" + userId).isPresent();
    }

    /**
     * Persists who posted the confession and mirrors an audit embed (with the
     * moderation buttons) into the bound confession log channel.
     */
    public void record(Guild guild, Message post, User author) {
        long postId = post.getIdLong();
        store.put(postPk(postId), "meta", Map.of(
                "guildId", guild.getId(),
                "channelId", post.getChannel().getId(),
                "authorId", author.getId(),
                "postedAt", Long.toString(Instant.now().getEpochSecond())));

        Long logChannelId = binds.channelFor(guild.getIdLong(), LogCategory.CONFESSION);
        if (logChannelId == null) return;
        GuildMessageChannel logChannel = guild.getChannelById(GuildMessageChannel.class, logChannelId);
        if (logChannel == null || !guild.getSelfMember().hasAccess(logChannel)) return;

        MessageEmbed embed = new EmbedBuilder()
                .setColor(C_AUDIT)
                .setAuthor("Confession posted")
                .addField("Post ID", "`" + post.getId() + "`", true)
                .addField("Author", "ID: `" + author.getId() + "`", true)
                .addField("Posted in", "<#" + post.getChannel().getId() + "> · [Jump]("
                        + post.getJumpUrl() + ")", false)
                .setFooter("Author shown by ID only — confessions stay anonymous in chat")
                .setTimestamp(Instant.now())
                .build();
        logChannel.sendMessageEmbeds(embed)
                .setComponents(ActionRow.of(
                        Button.danger(BTN_RESTRICT + postId, "Restrict user"),
                        Button.primary(BTN_WARN + postId, "Warn user")))
                .queue(v -> {}, err -> {});
    }

    // ---------------- buttons ----------------

    @Override
    public void onButtonInteraction(@NotNull ButtonInteractionEvent event) {
        String id = event.getComponentId();
        boolean restrict = id.startsWith(BTN_RESTRICT);
        if (!restrict && !id.startsWith(BTN_WARN)) return;

        Guild guild = event.getGuild();
        Member member = event.getMember();
        if (guild == null) return;
        if (!Capability.TIMEOUT_MEMBERS.grantedTo(member)) {
            event.replyEmbeds(Embeds.warn("You need **Moderate Members** to act on confessions."))
                    .setEphemeral(true).queue();
            return;
        }

        long postId = parseId(id.substring(restrict ? BTN_RESTRICT.length() : BTN_WARN.length()));
        Record record = load(postId);
        if (record == null) {
            event.replyEmbeds(Embeds.error("No audit record found for this confession."))
                    .setEphemeral(true).queue();
            return;
        }

        if (restrict) {
            toggleRestrict(event, guild, record, postId);
        } else {
            event.replyModal(warnModal(postId)).queue();
        }
    }

    private void toggleRestrict(ButtonInteractionEvent event, Guild guild, Record record, long postId) {
        String sk = "confess-block#" + record.authorId();
        boolean blocked = store.get(guildPk(guild.getIdLong()), sk).isPresent();
        if (blocked) {
            store.delete(guildPk(guild.getIdLong()), sk);
        } else {
            store.put(guildPk(guild.getIdLong()), sk, Map.of(
                    "byId", event.getUser().getId(),
                    "postId", Long.toString(postId),
                    "since", Long.toString(Instant.now().getEpochSecond())));
        }
        String action = blocked ? "lifted the `/confess` restriction for" : "restricted `/confess` for";
        event.replyEmbeds(Embeds.success("You " + action + " user ID: `" + record.authorId()
                        + "`." + (blocked ? "" : " Pressing the button again lifts it.")))
                .setEphemeral(true).queue();
        postToLog(guild, new EmbedBuilder()
                .setColor(C_AUDIT)
                .setAuthor(blocked ? "Confession restriction lifted" : "User restricted from /confess")
                .addField("User", "ID: `" + record.authorId() + "`", true)
                .addField("Post ID", "`" + postId + "`", true)
                .addField("Moderator", event.getUser().getAsMention(), true)
                .setTimestamp(Instant.now())
                .build());
    }

    private static Modal warnModal(long postId) {
        return Modal.create(MODAL_WARN + postId, "Warn confession author")
                .addComponents(
                        Label.of("Reason", "Delivered to the author by DM, with your name attached.",
                                TextInput.create(REASON_INPUT, TextInputStyle.PARAGRAPH)
                                        .setPlaceholder("Why is this confession being warned?")
                                        .setRequiredRange(1, MAX_REASON_LEN)
                                        .build()),
                        Label.of("Evidence image (optional)", "PNG only — anything else is skipped.",
                                AttachmentUpload.create(IMAGE_INPUT)
                                        .setRequired(false)
                                        .setMaxValues(1)
                                        .build()))
                .build();
    }

    // ---------------- warn modal submit ----------------

    @Override
    public void onModalInteraction(@NotNull ModalInteractionEvent event) {
        if (!event.getModalId().startsWith(MODAL_WARN)) return;
        Guild guild = event.getGuild();
        if (guild == null) return;
        if (!Capability.TIMEOUT_MEMBERS.grantedTo(event.getMember())) {
            event.replyEmbeds(Embeds.warn("You need **Moderate Members** to act on confessions."))
                    .setEphemeral(true).queue();
            return;
        }

        long postId = parseId(event.getModalId().substring(MODAL_WARN.length()));
        Record record = load(postId);
        if (record == null) {
            event.replyEmbeds(Embeds.error("No audit record found for this confession."))
                    .setEphemeral(true).queue();
            return;
        }

        String reason = value(event, REASON_INPUT).strip();
        if (reason.isEmpty()) {
            event.replyEmbeds(Embeds.error("The warning needs a reason — nothing was sent."))
                    .setEphemeral(true).queue();
            return;
        }
        if (reason.length() > MAX_REASON_LEN) {
            reason = reason.substring(0, MAX_REASON_LEN);
        }

        Message.Attachment image = firstAttachment(event);
        boolean imageSkipped = image != null && !isPng(image);
        if (imageSkipped) {
            image = null;
        }

        // DM + download can take a moment; acknowledge first.
        event.deferReply(true).queue();

        byte[] imageBytes = null;
        if (image != null) {
            imageBytes = download(image);
            if (imageBytes == null) {
                imageSkipped = true;
            }
        }

        store.put(postPk(postId), "warn#" + System.currentTimeMillis(), Map.of(
                "byId", event.getUser().getId(),
                "reason", reason));

        deliver(event, guild, record, postId, reason, imageBytes, imageSkipped);
    }

    private void deliver(ModalInteractionEvent event, Guild guild, Record record,
                         long postId, String reason, byte[] imageBytes, boolean imageSkipped) {
        final String note = imageSkipped
                ? "\n⚠️ The attached image was skipped — only PNG files are accepted."
                : "";

        EmbedBuilder dmEmbed = new EmbedBuilder()
                .setColor(C_WARN)
                .setAuthor("⚠️ Warning from the moderation team of " + guild.getName(),
                        null, guild.getIconUrl())
                .setDescription("Your recent anonymous confession was flagged by the moderation team.")
                .addField("Reason", reason, false)
                .setFooter("Repeated issues may lead to losing access to /confess")
                .setTimestamp(Instant.now());
        if (imageBytes != null) {
            dmEmbed.setImage("attachment://evidence.png");
        }

        final byte[] bytes = imageBytes;
        event.getJDA().retrieveUserById(record.authorId()).queue(author ->
                author.openPrivateChannel().queue(dm -> {
                    var action = dm.sendMessageEmbeds(dmEmbed.build());
                    if (bytes != null) {
                        action = action.addFiles(FileUpload.fromData(bytes, "evidence.png"));
                    }
                    action.queue(
                            sent -> finish(event, guild, record, postId, reason, bytes, true, note),
                            err -> finish(event, guild, record, postId, reason, bytes, false, note));
                }, err -> finish(event, guild, record, postId, reason, bytes, false, note)),
                err -> finish(event, guild, record, postId, reason, bytes, false, note));
    }

    private void finish(ModalInteractionEvent event, Guild guild, Record record,
                        long postId, String reason, byte[] imageBytes, boolean dmDelivered, String note) {
        EmbedBuilder logEmbed = new EmbedBuilder()
                .setColor(C_WARN)
                .setAuthor("Confession author warned")
                .addField("Post ID", "`" + postId + "`", true)
                .addField("Author", "ID: `" + record.authorId() + "`", true)
                .addField("Moderator", event.getUser().getAsMention(), true)
                .addField("Reason", reason, false)
                .addField("DM delivery", dmDelivered ? "✅ delivered" : "❌ failed (DMs closed?)", true)
                .setTimestamp(Instant.now());
        if (imageBytes != null) {
            logEmbed.setImage("attachment://evidence.png");
        }
        postToLog(guild, logEmbed.build(), imageBytes);

        event.getHook().editOriginalEmbeds(dmDelivered
                ? Embeds.success("Warning delivered by DM to user ID: `" + record.authorId() + "`." + note)
                : Embeds.warn("Warning logged, but the DM could not be delivered (the user likely has DMs closed)." + note))
                .queue(v -> {}, err -> {});
    }

    // ---------------- helpers ----------------

    private void postToLog(Guild guild, MessageEmbed embed) {
        postToLog(guild, embed, null);
    }

    private void postToLog(Guild guild, MessageEmbed embed, byte[] imageBytes) {
        Long channelId = binds.channelFor(guild.getIdLong(), LogCategory.CONFESSION);
        if (channelId == null) return;
        GuildMessageChannel channel = guild.getChannelById(GuildMessageChannel.class, channelId);
        if (channel == null || !guild.getSelfMember().hasAccess(channel)) return;
        var action = channel.sendMessageEmbeds(embed);
        if (imageBytes != null) {
            action = action.addFiles(FileUpload.fromData(imageBytes, "evidence.png"));
        }
        action.queue(v -> {}, err -> {});
    }

    private Record load(long postId) {
        Optional<Map<String, String>> meta = store.get(postPk(postId), "meta");
        if (meta.isEmpty()) return null;
        Map<String, String> attrs = meta.get();
        try {
            return new Record(
                    Long.parseLong(attrs.getOrDefault("guildId", "")),
                    Long.parseLong(attrs.getOrDefault("channelId", "")),
                    Long.parseLong(attrs.getOrDefault("authorId", "")),
                    Long.parseLong(attrs.getOrDefault("postedAt", "0")));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Message.Attachment firstAttachment(ModalInteractionEvent event) {
        ModalMapping mapping = event.getValue(IMAGE_INPUT);
        if (mapping == null) return null;
        List<Message.Attachment> attachments = mapping.getAsAttachmentList();
        return attachments.isEmpty() ? null : attachments.get(0);
    }

    private static boolean isPng(Message.Attachment attachment) {
        if (attachment.getSize() > MAX_IMAGE_BYTES) return false;
        String type = attachment.getContentType();
        if (type != null && type.toLowerCase(Locale.ROOT).startsWith("image/png")) return true;
        return attachment.getFileName().toLowerCase(Locale.ROOT).endsWith(".png");
    }

    private static byte[] download(Message.Attachment attachment) {
        try (InputStream in = attachment.getProxy().download().join()) {
            return in.readAllBytes();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static String value(ModalInteractionEvent event, String id) {
        ModalMapping mapping = event.getValue(id);
        if (mapping == null) return "";
        String raw = mapping.getAsOptionalString();
        return raw == null ? "" : raw;
    }

    private static long parseId(String raw) {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static String guildPk(long guildId) {
        return "guild#" + guildId;
    }

    private static String postPk(long postId) {
        return "confession#" + postId;
    }
}
