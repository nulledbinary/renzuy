package renzuy.commands;

import java.awt.Color;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.PermissionOverride;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.attribute.IPermissionContainer;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.modals.ModalMapping;
import net.dv8tion.jda.api.modals.Modal;
import org.jetbrains.annotations.NotNull;
import renzuy.store.StateStore;
import renzuy.ui.Embeds;

/**
 * {@code /lockdown}: toggles a full lock on the current channel. Strictly
 * limited to members with <b>Administrator</b> — no lesser permission grants
 * access.
 *
 * <p>Locking asks for a reason via modal, then denies {@code @everyone} the
 * send/thread/reaction permissions on the channel (administrators bypass
 * channel overrides by definition, so only they can keep talking). The
 * {@code @everyone} override that existed before the lock is captured in the
 * {@link StateStore}, so running {@code /lockdown} again — even after a bot
 * restart — restores the channel to exactly its previous state.
 */
public final class LockdownCommand extends ListenerAdapter {

    public static final String NAME = "lockdown";

    private static final String MODAL_PREFIX = "lockdown:modal:";
    private static final String REASON_INPUT = "lockdown:reason";

    private static final Color C_LOCK   = new Color(0xED4245);
    private static final Color C_UNLOCK = new Color(0x57F287);

    private static final long LOCK_BITS = Permission.getRaw(
            Permission.MESSAGE_SEND,
            Permission.MESSAGE_SEND_IN_THREADS,
            Permission.CREATE_PUBLIC_THREADS,
            Permission.CREATE_PRIVATE_THREADS,
            Permission.MESSAGE_ADD_REACTION);

    private final StateStore store;

    public LockdownCommand(StateStore store) {
        this.store = store;
    }

    // ---------------- /lockdown ----------------

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
        if (member == null || !member.hasPermission(Permission.ADMINISTRATOR)) {
            event.replyEmbeds(Embeds.warn("Only members with **Administrator** can use `/lockdown`."))
                    .setEphemeral(true).queue();
            return;
        }
        IPermissionContainer channel = containerOf(event);
        if (channel == null) {
            event.replyEmbeds(Embeds.error("Run `/lockdown` in a normal text channel (not a thread)."))
                    .setEphemeral(true).queue();
            return;
        }
        if (!guild.getSelfMember().hasPermission(channel, Permission.MANAGE_PERMISSIONS)) {
            event.replyEmbeds(Embeds.error("I need **Manage Permissions** on this channel to lock it down."))
                    .setEphemeral(true).queue();
            return;
        }

        if (store.get(pk(guild), sk(channel.getIdLong())).isPresent()) {
            unlock(event, guild, channel);
            return;
        }

        Modal modal = Modal.create(MODAL_PREFIX + channel.getId(), "Lock down #" + channel.getName())
                .addComponents(
                        Label.of("Reason", "Shown publicly in the lockdown notice.",
                                TextInput.create(REASON_INPUT, TextInputStyle.PARAGRAPH)
                                        .setPlaceholder("Why is this channel being locked down?")
                                        .setRequiredRange(1, 500)
                                        .build()))
                .build();
        event.replyModal(modal).queue();
    }

    // ---------------- modal submit → lock ----------------

    @Override
    public void onModalInteraction(@NotNull ModalInteractionEvent event) {
        if (!event.getModalId().startsWith(MODAL_PREFIX)) return;
        Guild guild = event.getGuild();
        Member member = event.getMember();
        if (guild == null || member == null || !member.hasPermission(Permission.ADMINISTRATOR)) return;

        long channelId;
        try {
            channelId = Long.parseLong(event.getModalId().substring(MODAL_PREFIX.length()));
        } catch (NumberFormatException e) {
            return;
        }
        IPermissionContainer channel = guild.getChannelById(IPermissionContainer.class, channelId);
        if (channel == null) {
            event.replyEmbeds(Embeds.error("That channel no longer exists.")).setEphemeral(true).queue();
            return;
        }
        if (store.get(pk(guild), sk(channelId)).isPresent()) {
            event.replyEmbeds(Embeds.warn("This channel is already locked down — run `/lockdown` to lift it."))
                    .setEphemeral(true).queue();
            return;
        }

        String reason = value(event, REASON_INPUT).strip();
        if (reason.isEmpty()) {
            event.replyEmbeds(Embeds.error("A reason is required to lock down the channel."))
                    .setEphemeral(true).queue();
            return;
        }
        if (reason.length() > 500) {
            reason = reason.substring(0, 500);
        }

        Role everyone = guild.getPublicRole();
        PermissionOverride existing = channel.getPermissionOverride(everyone);
        long oldAllow = existing == null ? 0L : existing.getAllowedRaw();
        long oldDeny  = existing == null ? 0L : existing.getDeniedRaw();

        store.put(pk(guild), sk(channelId), Map.of(
                "hadOverride", Boolean.toString(existing != null),
                "allow", Long.toString(oldAllow),
                "deny", Long.toString(oldDeny),
                "byId", member.getId(),
                "reason", reason,
                "since", Long.toString(Instant.now().getEpochSecond())));

        final String finalReason = reason;
        channel.upsertPermissionOverride(everyone)
                .setAllowed(oldAllow & ~LOCK_BITS)
                .setDenied(oldDeny | LOCK_BITS)
                .reason("Lockdown by " + member.getUser().getAsTag() + ": " + reason)
                .queue(v -> {
                    event.replyEmbeds(new EmbedBuilder()
                                    .setColor(C_LOCK)
                                    .setAuthor("🔒 Channel locked down")
                                    .setDescription("Only administrators can talk here until the lockdown is lifted.")
                                    .addField("Reason", finalReason, false)
                                    .addField("By", member.getAsMention(), true)
                                    .setTimestamp(Instant.now())
                                    .build())
                            .queue();
                }, err -> {
                    store.delete(pk(guild), sk(channelId));
                    event.replyEmbeds(Embeds.error("Could not apply the lockdown: " + err.getMessage()))
                            .setEphemeral(true).queue();
                });
    }

    // ---------------- second run → unlock ----------------

    private void unlock(SlashCommandInteractionEvent event, Guild guild, IPermissionContainer channel) {
        Optional<Map<String, String>> saved = store.get(pk(guild), sk(channel.getIdLong()));
        if (saved.isEmpty()) return;
        Map<String, String> attrs = saved.get();

        boolean hadOverride = Boolean.parseBoolean(attrs.getOrDefault("hadOverride", "false"));
        long allow = parseLong(attrs.get("allow"));
        long deny  = parseLong(attrs.get("deny"));
        Role everyone = guild.getPublicRole();
        String auditReason = "Lockdown lifted by " + event.getUser().getAsTag();

        Runnable announce = () -> {
            store.delete(pk(guild), sk(channel.getIdLong()));
            event.replyEmbeds(new EmbedBuilder()
                            .setColor(C_UNLOCK)
                            .setAuthor("🔓 Lockdown lifted")
                            .setDescription("Channel permissions restored — everyone can talk again.")
                            .addField("By", event.getUser().getAsMention(), true)
                            .setTimestamp(Instant.now())
                            .build())
                    .queue();
        };

        if (hadOverride) {
            channel.upsertPermissionOverride(everyone)
                    .setAllowed(allow)
                    .setDenied(deny)
                    .reason(auditReason)
                    .queue(v -> announce.run(),
                            err -> event.replyEmbeds(Embeds.error("Could not restore permissions: "
                                    + err.getMessage())).setEphemeral(true).queue());
        } else {
            PermissionOverride override = channel.getPermissionOverride(everyone);
            if (override == null) {
                announce.run();
                return;
            }
            override.delete().reason(auditReason)
                    .queue(v -> announce.run(),
                            err -> event.replyEmbeds(Embeds.error("Could not restore permissions: "
                                    + err.getMessage())).setEphemeral(true).queue());
        }
    }

    // ---------------- helpers ----------------

    private static IPermissionContainer containerOf(SlashCommandInteractionEvent event) {
        if (!event.getChannelType().isMessage() || event.getChannelType().isThread()) return null;
        if (event.getChannel() instanceof GuildMessageChannel channel
                && channel instanceof IPermissionContainer container) {
            return container;
        }
        return null;
    }

    private static String value(ModalInteractionEvent event, String id) {
        ModalMapping mapping = event.getValue(id);
        if (mapping == null) return "";
        String raw = mapping.getAsOptionalString();
        return raw == null ? "" : raw;
    }

    private static long parseLong(String raw) {
        try {
            return raw == null ? 0L : Long.parseLong(raw);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static String pk(Guild guild) {
        return "guild#" + guild.getId();
    }

    private static String sk(long channelId) {
        return "lockdown#" + channelId;
    }
}
