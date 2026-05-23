package renzuy.commands.text;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.jetbrains.annotations.NotNull;
import renzuy.config.PrefixStore;

/**
 * Single listener that dispatches prefix-based text commands.
 *
 * <p>Routing rules:
 * <ul>
 *   <li>Only fires on guild messages whose first character is the guild's prefix.</li>
 *   <li>Ignores bots and messages without {@code MESSAGE_CONTENT} content.</li>
 *   <li>The text after the prefix is split into {@code name} and {@code args}; the name
 *       is lowercased for case-insensitive matching.</li>
 * </ul>
 */
public final class TextCommandRouter extends ListenerAdapter {

    private final PrefixStore prefixes;
    private final Map<String, TextCommand> commands = new HashMap<>();

    public TextCommandRouter(PrefixStore prefixes, TextCommand... commands) {
        this.prefixes = prefixes;
        for (TextCommand command : commands) {
            this.commands.put(command.name().toLowerCase(Locale.ROOT), command);
        }
    }

    @Override
    public void onMessageReceived(@NotNull MessageReceivedEvent event) {
        if (event.getAuthor().isBot() || !event.isFromGuild()) {
            return;
        }
        Guild guild = event.getGuild();
        String content = event.getMessage().getContentRaw();
        if (content.isEmpty()) {
            return;
        }
        String prefix = prefixes.get(guild.getIdLong());
        if (!content.startsWith(prefix)) {
            return;
        }
        String body = content.substring(prefix.length()).stripLeading();
        if (body.isEmpty()) {
            return;
        }
        int space = body.indexOf(' ');
        String name = (space < 0 ? body : body.substring(0, space)).toLowerCase(Locale.ROOT);
        String args = space < 0 ? "" : body.substring(space + 1).strip();
        TextCommand command = commands.get(name);
        if (command == null) {
            return;
        }
        try {
            command.execute(event, args);
        } catch (RuntimeException e) {
            System.err.println("[TextCommand] " + name + " threw: " + e.getMessage());
        }
    }
}
