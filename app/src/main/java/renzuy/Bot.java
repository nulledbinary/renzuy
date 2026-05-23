package renzuy;

import club.minnced.discord.jdave.interop.JDaveSessionFactory;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.OnlineStatus;
import net.dv8tion.jda.api.audio.AudioModuleConfig;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.MemberCachePolicy;
import net.dv8tion.jda.api.utils.cache.CacheFlag;
import renzuy.audio.MusicService;
import renzuy.commands.CommandRegistrar;
import renzuy.commands.HelpCommand;
import renzuy.commands.PlayCommand;
import renzuy.commands.QueueCommand;
import renzuy.commands.RemoveCommand;
import renzuy.commands.SkipCommand;
import renzuy.commands.StopCommand;
import renzuy.listeners.MessageListener;

public final class Bot {

    public static void main(String[] args) throws InterruptedException {
        String token = DotEnv.get("DISCORD_TOKEN");
        if (token == null || token.isBlank()) {
            System.err.println("DISCORD_TOKEN is not set. Put it in a .env file at the project root, "
                    + "or set it as an environment variable.");
            System.exit(1);
        }

        MusicService music = new MusicService();

        SlashCommandData[] commands = {
                Commands.slash(HelpCommand.NAME, "Show the list of available commands"),
                Commands.slash(PlayCommand.NAME, "Play a track, search, or playlist")
                        .addOption(OptionType.STRING, PlayCommand.QUERY_OPTION,
                                "URL, playlist URL (YouTube / YouTube Music / SoundCloud set), or search term", true),
                Commands.slash(StopCommand.NAME, "Stop playback and clear the queue"),
                Commands.slash(SkipCommand.NAME, "Skip the current song"),
                Commands.slash(QueueCommand.NAME, "Show the queue (only visible to you)"),
                Commands.slash(RemoveCommand.NAME, "Remove a track from the queue by its number")
                        .addOptions(new OptionData(OptionType.INTEGER, RemoveCommand.POSITION_OPTION,
                                "Position in the queue (1 = next up)", true)
                                .setMinValue(1))
        };

        JDA jda = JDABuilder.createLight(token,
                        GatewayIntent.GUILD_MESSAGES,
                        GatewayIntent.MESSAGE_CONTENT,
                        GatewayIntent.GUILD_VOICE_STATES,
                        GatewayIntent.GUILD_MEMBERS)
                .setAudioModuleConfig(new AudioModuleConfig()
                        .withDaveSessionFactory(new JDaveSessionFactory()))
                .enableCache(CacheFlag.VOICE_STATE)
                .setMemberCachePolicy(MemberCachePolicy.VOICE)
                // Show a starting status immediately; PresenceUpdater takes over after ready.
                .setStatus(OnlineStatus.IDLE)
                .setActivity(Activity.customStatus("Booting…"))
                .addEventListeners(
                        new HelpCommand(),
                        new MessageListener(),
                        new PlayCommand(music),
                        new StopCommand(music),
                        new SkipCommand(music),
                        new QueueCommand(music),
                        new RemoveCommand(music),
                        new CommandRegistrar(commands)
                )
                .build()
                .awaitReady();

        System.out.println("Logged in as " + jda.getSelfUser().getAsTag());

        PresenceUpdater presence = new PresenceUpdater(jda);
        presence.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("[Shutdown] Stopping presence updater and JDA");
            presence.shutdown();
            jda.shutdown();
        }, "shutdown-hook"));
    }
}
