package renzuy.commands;

import java.util.List;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.jetbrains.annotations.NotNull;

public final class HelpCommand extends ListenerAdapter {

    public static final String NAME = "help";

    private static final List<Entry> COMMANDS = List.of(
            new Entry("/help", "Shows the list of available commands"),
            new Entry("/play <query>", "Plays audio from YouTube — paste a link or type a search term")
    );

    @Override
    public void onSlashCommandInteraction(@NotNull SlashCommandInteractionEvent event) {
        if (!event.getName().equals(NAME)) {
            return;
        }

        StringBuilder body = new StringBuilder("**Available commands**\n");
        for (Entry entry : COMMANDS) {
            body.append("> ").append(entry.usage).append(" — ").append(entry.description).append('\n');
        }

        event.reply(body.toString().stripTrailing())
                .setEphemeral(true)
                .queue();
    }

    private record Entry(String usage, String description) {}
}
