package renzuy.commands;

import java.util.List;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.jetbrains.annotations.NotNull;
import renzuy.commands.text.TextCommand;
import renzuy.config.PrefixStore;
import renzuy.ui.Embeds;

public final class HelpCommand extends ListenerAdapter implements TextCommand {

    public static final String NAME = "help";

    private static final List<Entry> COMMANDS = List.of(
            new Entry("help", "Shows the list of available commands"),
            new Entry("play <query>", "Plays a track, search term, or playlist URL"),
            new Entry("stop", "Stops playback and clears the queue"),
            new Entry("skip", "Skips the current song"),
            new Entry("queue", "Shows the queue (paging buttons on slash version)"),
            new Entry("remove <position>", "Removes a track from the queue by its number"),
            new Entry("info [user]", "Shows account / server-join info for a user"),
            new Entry("prefix <char>", "Admin: change the text-command prefix (slash only)")
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
        String prefix = event.getGuild() != null
                ? prefixes.get(event.getGuild().getIdLong())
                : PrefixStore.DEFAULT_PREFIX;
        event.replyEmbeds(buildEmbed(prefix)).setEphemeral(true).queue();
    }

    @Override
    public void execute(MessageReceivedEvent event, String args) {
        String prefix = prefixes.get(event.getGuild().getIdLong());
        event.getChannel().sendMessageEmbeds(buildEmbed(prefix)).queue();
    }

    private static MessageEmbed buildEmbed(String prefix) {
        StringBuilder body = new StringBuilder();
        for (Entry entry : COMMANDS) {
            body.append("> `/").append(entry.usage).append("`  or  `")
                    .append(prefix).append(entry.usage).append("` — ")
                    .append(entry.description).append('\n');
        }
        return new EmbedBuilder()
                .setColor(Embeds.INFO)
                .setTitle("Available commands")
                .setDescription(body.toString().stripTrailing())
                .setFooter("Current text prefix: " + prefix)
                .build();
    }

    private record Entry(String usage, String description) {}
}
