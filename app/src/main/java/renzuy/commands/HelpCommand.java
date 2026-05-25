package renzuy.commands;

import java.util.ArrayList;
import java.util.List;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.jetbrains.annotations.NotNull;
import renzuy.commands.text.TextCommand;
import renzuy.config.PrefixStore;

/**
 * {@code /help} (and {@code <prefix>help}): renders a plain-text command listing
 * filtered by what the invoking member can actually run.
 *
 * <p>Output is plain content (no embed) so the format renders identically across
 * mobile/desktop/web and stays grep-friendly. Three sections:
 * <ol>
 *   <li><b>Available commands</b> — everything {@link Capability#EVERYONE} grants;
 *       shown to every invocation.</li>
 *   <li><b>Management commands</b> — anything the member has at least one
 *       moderation capability for. Hidden entirely if the member has none.</li>
 *   <li><b>Administrative</b> — server-config commands (prefix, log binding);
 *       hidden unless the member has the matching permission.</li>
 * </ol>
 */
public final class HelpCommand extends ListenerAdapter implements TextCommand {

    public static final String NAME = "help";

    private record Entry(String usage, String description, Capability capability) {}

    private static final List<Entry> GENERAL = List.of(
            new Entry("help",                 "Show this list",                                       Capability.EVERYONE),
            new Entry("play <query>",         "Play a track, search term, or playlist URL",           Capability.EVERYONE),
            new Entry("stop",                 "Stop playback and clear the queue",                    Capability.EVERYONE),
            new Entry("skip",                 "Skip the current song",                                Capability.EVERYONE),
            new Entry("queue",                "Show the queue (paging buttons on slash version)",     Capability.EVERYONE),
            new Entry("remove <position>",    "Remove a track from the queue by its number",          Capability.EVERYONE),
            new Entry("info [user]",          "Detailed account / server info for a user",            Capability.EVERYONE)
    );

    private static final List<Entry> MANAGEMENT = List.of(
            new Entry("purge <count>",                    "Bulk-delete up to 100 recent messages",          Capability.PURGE_MESSAGES),
            new Entry("tempmute <user> <duration>",       "Discord timeout — e.g. 30s, 15m, 2h, 7d",        Capability.TIMEOUT_MEMBERS),
            new Entry("tempban <user> <duration>",        "Ban with scheduled auto-unban",                  Capability.BAN_MEMBERS),
            new Entry("log",                              "Bind this channel as the server event log",      Capability.VIEW_LOGS)
    );

    private static final List<Entry> ADMIN = List.of(
            new Entry("prefix <char>",        "Change the text-command prefix (slash only)",          Capability.MANAGE_PREFIX)
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
        String body = render(event.getMember(), prefix);
        event.reply(body).setEphemeral(true).queue();
    }

    @Override
    public void execute(MessageReceivedEvent event, String args) {
        String prefix = prefixes.get(event.getGuild().getIdLong());
        String body = render(event.getMember(), prefix);
        event.getChannel().sendMessage(body).queue();
    }

    /**
     * Renders the command listing filtered to what {@code member} can actually
     * invoke. Plain Markdown, no embed; sections are hidden entirely when the
     * member has no commands in them, so an unprivileged user sees only the
     * music block.
     */
    static String render(Member member, String prefix) {
        StringBuilder out = new StringBuilder(512);
        out.append("**Available commands**\n");
        appendSection(out, GENERAL, member, prefix);

        List<Entry> mgmt = filtered(MANAGEMENT, member);
        if (!mgmt.isEmpty()) {
            out.append("\n**Management commands**\n");
            appendSection(out, mgmt, member, prefix);
        }

        List<Entry> admin = filtered(ADMIN, member);
        if (!admin.isEmpty()) {
            out.append("\n**Administrative**\n");
            appendSection(out, admin, member, prefix);
        }

        out.append("\nCurrent text prefix: `").append(prefix).append('`');
        return out.toString();
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

    private static void appendSection(StringBuilder out, List<Entry> entries, Member member, String prefix) {
        for (Entry entry : entries) {
            if (!entry.capability().grantedTo(member)) continue;
            out.append("`/").append(entry.usage()).append("`  or  `")
                    .append(prefix).append(entry.usage()).append("` — ")
                    .append(entry.description()).append('\n');
        }
    }
}
