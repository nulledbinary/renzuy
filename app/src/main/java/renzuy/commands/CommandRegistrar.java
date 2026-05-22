package renzuy.commands;

import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.events.guild.GuildJoinEvent;
import net.dv8tion.jda.api.events.guild.GuildReadyEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import org.jetbrains.annotations.NotNull;

public final class CommandRegistrar extends ListenerAdapter {

    private final SlashCommandData[] commands;

    public CommandRegistrar(SlashCommandData... commands) {
        this.commands = commands;
    }

    @Override
    public void onReady(@NotNull ReadyEvent event) {
        event.getJDA().updateCommands().queue(
                result -> System.out.println("Cleared global commands (now " + result.size() + ")"),
                error -> System.err.println("Failed to clear global commands: " + error.getMessage())
        );
    }

    @Override
    public void onGuildReady(@NotNull GuildReadyEvent event) {
        register(event.getGuild());
    }

    @Override
    public void onGuildJoin(@NotNull GuildJoinEvent event) {
        register(event.getGuild());
    }

    private void register(Guild guild) {
        guild.updateCommands()
                .addCommands(commands)
                .queue(
                        result -> System.out.println(
                                "Registered " + result.size() + " commands in " + guild.getName() + " (" + guild.getId() + ")"),
                        error -> System.err.println(
                                "Failed to register commands in " + guild.getName() + " (" + guild.getId() + "): " + error.getMessage())
                );
    }
}
