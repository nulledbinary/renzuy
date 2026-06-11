package renzuy.commands;

import java.awt.Color;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.selections.SelectOption;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.modals.ModalMapping;
import net.dv8tion.jda.api.modals.Modal;
import org.jetbrains.annotations.NotNull;
import renzuy.ui.Embeds;

/**
 * {@code /confess}: opens a modal form and posts the submission as an
 * anonymous embed in the channel the command was used in.
 *
 * <p>The form collects the confession body, an optional direct image URL,
 * whether replies are welcome, and the embed colour. When replies are allowed
 * the bot opens a public thread under the confession so reactions stay in one
 * place; otherwise no thread is created.
 *
 * <p><b>Anonymity:</b> the author is never logged, embedded, or persisted —
 * the only acknowledgement is the ephemeral confirmation that Discord routes
 * back to the submitter. Image URLs must be {@code https} direct image links
 * (or a known image CDN); anything else is dropped rather than rendered.
 */
public final class ConfessCommand extends ListenerAdapter {

    public static final String NAME = "confess";

    private static final String MODAL_ID     = "confess:modal";
    private static final String TEXT_INPUT   = "confess:text";
    private static final String IMAGE_INPUT  = "confess:image";
    private static final String REPLIES_MENU = "confess:replies";
    private static final String COLOR_MENU   = "confess:color";

    private static final String RANDOM_COLOR = "random";
    private static final int MAX_CONFESSION_LEN = 2000;

    /** Palette offered in the colour select — label → hex (no leading #). */
    private static final List<SelectOption> COLOR_OPTIONS = List.of(
            SelectOption.of("Blurple", "5865F2").withDefault(true),
            SelectOption.of("Red",     "ED4245"),
            SelectOption.of("Orange",  "E67E22"),
            SelectOption.of("Yellow",  "FEE75C"),
            SelectOption.of("Green",   "57F287"),
            SelectOption.of("Blue",    "3498DB"),
            SelectOption.of("Purple",  "9B59B6"),
            SelectOption.of("Pink",    "EB459E"),
            SelectOption.of("Random",  RANDOM_COLOR)
    );

    // ---------------- /confess → modal ----------------

    @Override
    public void onSlashCommandInteraction(@NotNull SlashCommandInteractionEvent event) {
        if (!event.getName().equals(NAME)) return;
        if (event.getGuild() == null) {
            event.replyEmbeds(Embeds.warn("This command can only be used in a server."))
                    .setEphemeral(true).queue();
            return;
        }
        if (!event.getChannel().getType().isMessage()) {
            event.replyEmbeds(Embeds.error("Run `/confess` in a text channel — that's where the confession is posted."))
                    .setEphemeral(true).queue();
            return;
        }

        Modal modal = Modal.create(MODAL_ID, "Anonymous Confession")
                .addComponents(
                        Label.of("Confession", "Only the confession is posted — never your name.",
                                TextInput.create(TEXT_INPUT, TextInputStyle.PARAGRAPH)
                                        .setPlaceholder("What's on your mind?")
                                        .setRequiredRange(1, MAX_CONFESSION_LEN)
                                        .build()),
                        Label.of("Image URL (optional)", "Direct https image/GIF link, e.g. ….png or a Tenor page.",
                                TextInput.create(IMAGE_INPUT, TextInputStyle.SHORT)
                                        .setPlaceholder("https://…")
                                        .setRequired(false)
                                        .setMaxLength(500)
                                        .build()),
                        Label.of("Allow replies?",
                                StringSelectMenu.create(REPLIES_MENU)
                                        .addOptions(
                                                SelectOption.of("Yes — open a reply thread", "yes").withDefault(true),
                                                SelectOption.of("No — just post it", "no"))
                                        .build()),
                        Label.of("Embed color",
                                StringSelectMenu.create(COLOR_MENU)
                                        .addOptions(COLOR_OPTIONS)
                                        .build()))
                .build();
        event.replyModal(modal).queue();
    }

    // ---------------- modal submit → anonymous post ----------------

    @Override
    public void onModalInteraction(@NotNull ModalInteractionEvent event) {
        if (!event.getModalId().equals(MODAL_ID)) return;
        if (event.getGuild() == null) return;

        GuildMessageChannel channel = event.getGuildChannel();
        if (!channel.canTalk()) {
            event.replyEmbeds(Embeds.error("I can't send messages in this channel, so the confession was not posted."))
                    .setEphemeral(true).queue();
            return;
        }

        String text = value(event, TEXT_INPUT);
        if (text.isBlank()) {
            event.replyEmbeds(Embeds.error("The confession was empty — nothing was posted."))
                    .setEphemeral(true).queue();
            return;
        }
        if (text.length() > MAX_CONFESSION_LEN) {
            text = text.substring(0, MAX_CONFESSION_LEN);
        }

        String imageUrl = value(event, IMAGE_INPUT).strip();
        boolean imageSkipped = !imageUrl.isEmpty() && !isSafeImageUrl(imageUrl);
        boolean allowReplies = "yes".equals(firstSelection(event, REPLIES_MENU, "yes"));
        Color color = resolveColor(firstSelection(event, COLOR_MENU, "5865F2"));

        EmbedBuilder b = new EmbedBuilder()
                .setColor(color)
                .setAuthor("🤫 Anonymous Confession")
                .setDescription(text)
                .setFooter(allowReplies
                        ? "Reply in the thread below · posted with /confess"
                        : "Replies are off for this one · posted with /confess");
        if (!imageUrl.isEmpty() && !imageSkipped) {
            b.setImage(imageUrl);
        }
        MessageEmbed embed = b.build();

        boolean canThread = event.getGuild().getSelfMember()
                .hasPermission(channel, Permission.CREATE_PUBLIC_THREADS);
        boolean openThread = allowReplies && canThread;

        String ack = "Your confession has been posted **anonymously**."
                + (imageSkipped ? "\n⚠️ The image was skipped — it wasn't a direct `https` image link." : "")
                + (allowReplies && !canThread ? "\n⚠️ I lack **Create Public Threads** here, so no reply thread was opened." : "");

        channel.sendMessageEmbeds(embed).queue(message -> {
            if (openThread) {
                message.createThreadChannel("💬 Confession replies")
                        .queue(t -> {}, err -> {});
            }
        }, err -> {});
        event.replyEmbeds(Embeds.success(ack)).setEphemeral(true).queue();
    }

    // ---------------- helpers ----------------

    private static String value(ModalInteractionEvent event, String id) {
        ModalMapping mapping = event.getValue(id);
        if (mapping == null) return "";
        String raw = mapping.getAsOptionalString();
        return raw == null ? "" : raw;
    }

    private static String firstSelection(ModalInteractionEvent event, String id, String fallback) {
        ModalMapping mapping = event.getValue(id);
        if (mapping == null) return fallback;
        List<String> selected = mapping.getAsStringList();
        return selected.isEmpty() ? fallback : selected.get(0);
    }

    private static Color resolveColor(String value) {
        if (RANDOM_COLOR.equals(value)) {
            return Color.getHSBColor(ThreadLocalRandom.current().nextFloat(), 0.65f, 0.95f);
        }
        try {
            return new Color(Integer.parseInt(value, 16));
        } catch (NumberFormatException e) {
            return Embeds.QUEUED;
        }
    }

    /** Accepts only https direct-image links or well-known image CDNs. */
    private static boolean isSafeImageUrl(String url) {
        if (!url.startsWith("https://") || url.contains(" ")) return false;
        String lower = url.toLowerCase();
        String path = lower;
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);
        if (path.endsWith(".png") || path.endsWith(".jpg") || path.endsWith(".jpeg")
                || path.endsWith(".gif") || path.endsWith(".webp") || path.endsWith(".apng")) {
            return true;
        }
        return lower.contains("tenor.com/view/") || lower.contains("giphy.com")
                || lower.contains("cdn.discordapp.com/attachments")
                || lower.contains("media.discordapp.net");
    }
}
