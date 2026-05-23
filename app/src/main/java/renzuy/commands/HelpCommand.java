package renzuy.commands;

import java.util.List;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.jetbrains.annotations.NotNull;
import renzuy.ui.Embeds;

public final class HelpCommand extends ListenerAdapter {

    public static final String NAME = "help";

    private static final List<Entry> COMMANDS = List.of(
            new Entry("/help", "Shows the list of available commands"),
            new Entry("/play <query>", "Plays a track, search term, or a playlist URL (YouTube / YouTube Music / SoundCloud set)"),
            new Entry("/stop", "Stops playback and clears the queue"),
            new Entry("/skip", "Skips the current song"),
            new Entry("/queue", "Shows the queue with paging buttons (only visible to you)"),
            new Entry("/remove <position>", "Removes a track from the queue by its number (see /queue)")
    );

    @Override
    public void onSlashCommandInteraction(@NotNull SlashCommandInteractionEvent event) {
        if (!event.getName().equals(NAME)) {
            return;
        }

        StringBuilder body = new StringBuilder();
        for (Entry entry : COMMANDS) {
            body.append("> `").append(entry.usage).append("` — ").append(entry.description).append('\n');
        }

        MessageEmbed embed = new EmbedBuilder()
                .setColor(Embeds.INFO)
                .setTitle("Available commands")
                .setDescription(body.toString().stripTrailing())
                .build();

        event.replyEmbeds(embed).setEphemeral(true).queue();
    }

    private record Entry(String usage, String description) {}
}
