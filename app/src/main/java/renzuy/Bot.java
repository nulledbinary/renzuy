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
import renzuy.commands.InfoCommand;
import renzuy.commands.PlayCommand;
import renzuy.commands.PrefixCommand;
import renzuy.commands.QueueCommand;
import renzuy.commands.RemoveCommand;
import renzuy.commands.SkipCommand;
import renzuy.commands.StopCommand;
import renzuy.commands.text.TextCommandRouter;
import renzuy.config.PrefixStore;
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
        PrefixStore prefixes = PrefixStore.defaultLocation();

        HelpCommand helpCommand = new HelpCommand(prefixes);
        PlayCommand playCommand = new PlayCommand(music);
        StopCommand stopCommand = new StopCommand(music);
        SkipCommand skipCommand = new SkipCommand(music);
        QueueCommand queueCommand = new QueueCommand(music);
        RemoveCommand removeCommand = new RemoveCommand(music);
        InfoCommand infoCommand = new InfoCommand();
        PrefixCommand prefixCommand = new PrefixCommand(prefixes);

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
                                .setMinValue(1)),
                Commands.slash(InfoCommand.NAME, "Show profile information for a user")
                        .addOption(OptionType.USER, InfoCommand.OPTION,
                                "The user to look up — defaults to you", false),
                Commands.slash(PrefixCommand.NAME, "Admin: set the text-command prefix (single special character)")
                        .addOption(OptionType.STRING, PrefixCommand.OPTION,
                                "A single special character, e.g. ! @ # $ % ^ &", true)
        };

        TextCommandRouter router = new TextCommandRouter(prefixes,
                helpCommand, playCommand, stopCommand, skipCommand,
                queueCommand, removeCommand, infoCommand);

        JDA jda = JDABuilder.createLight(token,
                        GatewayIntent.GUILD_MESSAGES,
                        GatewayIntent.MESSAGE_CONTENT,
                        GatewayIntent.GUILD_VOICE_STATES,
                        GatewayIntent.GUILD_MEMBERS)
                .setAudioModuleConfig(new AudioModuleConfig()
                        .withDaveSessionFactory(new JDaveSessionFactory()))
                .enableCache(CacheFlag.VOICE_STATE)
                .setMemberCachePolicy(MemberCachePolicy.VOICE)
                .setStatus(OnlineStatus.IDLE)
                .setActivity(Activity.customStatus("Booting…"))
                .addEventListeners(
                        helpCommand, playCommand, stopCommand, skipCommand,
                        queueCommand, removeCommand, infoCommand, prefixCommand,
                        router,
                        new MessageListener(),
                        new CommandRegistrar(commands))
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
