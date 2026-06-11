package renzuy.commands;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.jetbrains.annotations.NotNull;
import renzuy.commands.text.TextCommand;
import renzuy.config.PrefixStore;

/**
 * {@code /help} (and {@code <prefix>help}): renders a permission-filtered
 * embed listing commands the invoking member can actually run.
 *
 * <p>Slash invocation is ephemeral — only the caller sees it. Prefix invocation
 * cannot be ephemeral (messages can't), so to honour the "others shouldn't see
 * commands they can't run" rule the bot DMs the embed to the caller, deletes
 * the invoking message, and posts a short in-channel acknowledgement that
 * auto-deletes after a few seconds.
 */
public final class HelpCommand extends ListenerAdapter implements TextCommand {

    public static final String NAME = "help";
    private static final Color HELP_COLOR = new Color(0x5865F2);
    private static final long ACK_AUTO_DELETE_SECONDS = 8L;

    private record Entry(String usage, String description, Capability capability) {}

    private static final List<Entry> GENERAL = List.of(
            new Entry("help", "Show this list", Capability.EVERYONE),
            new Entry("play <query>", "Play a track, search term, or playlist URL", Capability.EVERYONE),
            new Entry("stop", "Stop playback and clear the queue", Capability.EVERYONE),
            new Entry("skip", "Skip the current song", Capability.EVERYONE),
            new Entry("queue", "Show the queue (paging buttons on slash version)", Capability.EVERYONE),
            new Entry("remove <position>", "Remove a track from the queue by its number", Capability.EVERYONE),
            new Entry("info [user]", "Detailed account / server info for a user", Capability.EVERYONE),
            new Entry("afk [reason]", "Mark yourself AFK — reason may include an image/GIF URL", Capability.EVERYONE),
            new Entry("confess", "Share an anonymous confession via a private form (slash only)", Capability.EVERYONE)
    );

    private static final List<Entry> MANAGEMENT = List.of(
            new Entry("purge <count>", "Bulk-delete up to 100 recent messages", Capability.PURGE_MESSAGES),
            new Entry("tempmute <user> <duration>", "Discord timeout — e.g. 30s, 15m, 2h, 7d", Capability.TIMEOUT_MEMBERS),
            new Entry("tempban <user> <duration>", "Ban with scheduled auto-unban", Capability.BAN_MEMBERS),
            new Entry("bind <category>", "Route a log category (confession, nickname, …) to this channel (slash only)", Capability.VIEW_LOGS),
            new Entry("unbind <category>", "Stop routing a log category (slash only)", Capability.VIEW_LOGS),
            new Entry("hatewarn <count> <punishment>", "Configure warnings threshold + punishment for hate-speech (slash only)", Capability.TIMEOUT_MEMBERS),
            new Entry("tambay", "Join the voice channel to idle and chill (slash only)", Capability.MANAGE_SERVER),
            new Entry("count", "Bind this channel for the counting game (slash only)", Capability.MANAGE_SERVER)
    );

    private static final List<Entry> ADMIN = List.of(
            new Entry("prefix <char>", "Change the text-command prefix (slash only)", Capability.MANAGE_PREFIX),
            new Entry("lockdown", "Lock / unlock the current channel — admins only (slash only)", Capability.ADMINISTRATOR)
    );

    private final PrefixStore prefixes;

    public HelpCommand(PrefixStore prefixes) {
        this.prefixes = prefixes;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void onSlashCommandInteraction(@NotNull SlashCommandInteractionEvent event) {
        if (!event.getName().equals(NAME)) return;
        Guild guild = event.getGuild();
        String prefix = guild != null ? prefixes.get(guild.getIdLong()) : PrefixStore.DEFAULT_PREFIX;
        MessageEmbed embed = render(event.getMember(), prefix);
        event.replyEmbeds(embed).setEphemeral(true).queue();
    }

    @Override
    public void execute(MessageReceivedEvent event, String args) {
        String prefix = prefixes.get(event.getGuild().getIdLong());
        Member member = event.getMember();
        User author = event.getAuthor();
        MessageEmbed embed = render(member, prefix);

        author.openPrivateChannel().queue(
                dm -> dm.sendMessageEmbeds(embed).queue(
                        sent -> {
                            event.getMessage().delete().queue(v -> {}, e -> {});
                            event.getChannel().sendMessage(
                                    author.getAsMention() + " — check your DMs for the command list.")
                                    .queue(msg -> msg.delete().queueAfter(
                                            ACK_AUTO_DELETE_SECONDS, TimeUnit.SECONDS, v -> {}, e -> {}),
                                            e -> {});
                        },
                        err -> fallbackInChannel(event, author)),
                err -> fallbackInChannel(event, author));
    }

    /**
     * DMs failed (closed DMs) — never dump the listing in channel: it reveals
     * which commands the caller can run to everyone watching. Point them at
     * {@code /help} instead, whose reply is ephemeral, and self-destruct the hint.
     */
    private static void fallbackInChannel(MessageReceivedEvent event, User author) {
        event.getChannel().sendMessage(author.getAsMention()
                        + " — your DMs are closed, so I can't send the command list privately."
                        + " Use **/help** instead; its reply is only visible to you.")
                .queue(msg -> msg.delete().queueAfter(ACK_AUTO_DELETE_SECONDS, TimeUnit.SECONDS, v -> {}, e -> {}),
                        e -> {});
        event.getMessage().delete().queue(v -> {}, e -> {});
    }

    /** Renders the embed with sections hidden for capabilities the member lacks. */
    static MessageEmbed render(Member member, String prefix) {
        EmbedBuilder b = new EmbedBuilder()
                .setColor(HELP_COLOR)
                .setAuthor("Commands available to you", null,
                        member != null ? member.getUser().getEffectiveAvatarUrl() : null)
                .setDescription("Each command can be invoked with `/` (slash) or with the text prefix `"
                        + prefix + "`.\nCommands you don't have permission to run are hidden.");

        appendField(b, "🌟 General", GENERAL, member, prefix);
        appendField(b, "🛡️ Management", MANAGEMENT, member, prefix);
        appendField(b, "⚙️ Administrative", ADMIN, member, prefix);

        b.setFooter("Current text prefix: " + prefix);
        return b.build();
    }

    private static void appendField(EmbedBuilder b, String title, List<Entry> entries, Member member, String prefix) {
        List<Entry> usable = filtered(entries, member);
        if (usable.isEmpty()) return;
        StringBuilder body = new StringBuilder(usable.size() * 64);
        for (Entry e : usable) {
            body.append("**/").append(e.usage()).append("** | **")
                    .append(prefix).append(e.usage()).append("**\n└ ")
                    .append(e.description()).append("\n\n");
        }
        b.addField(title, body.toString(), false);
    }

    private static List<Entry> filtered(List<Entry> entries, Member member) {
        List<Entry> out = new ArrayList<>(entries.size());
        for (Entry e : entries) {
            if (e.capability().grantedTo(member)) {
                out.add(e);
            }
        }
        return out;
    }
}
