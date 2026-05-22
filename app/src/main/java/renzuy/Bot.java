package renzuy;

import club.minnced.discord.jdave.interop.JDaveSessionFactory;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.audio.AudioModuleConfig;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.MemberCachePolicy;
import net.dv8tion.jda.api.utils.cache.CacheFlag;
import renzuy.audio.MusicService;
import renzuy.commands.CommandRegistrar;
import renzuy.commands.HelpCommand;
import renzuy.commands.PlayCommand;
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
                Commands.slash(PlayCommand.NAME, "Play audio from YouTube or a search term")
                        .addOption(OptionType.STRING, PlayCommand.QUERY_OPTION,
                                "YouTube URL or search term (also accepts Spotify, SoundCloud, ... links)", true)
        };

        JDA jda = JDABuilder.createLight(token,
                        GatewayIntent.GUILD_MESSAGES,
                        GatewayIntent.MESSAGE_CONTENT,
                        GatewayIntent.GUILD_VOICE_STATES)
                .setAudioModuleConfig(new AudioModuleConfig()
                        .withDaveSessionFactory(new JDaveSessionFactory()))
                .enableCache(CacheFlag.VOICE_STATE)
                .setMemberCachePolicy(MemberCachePolicy.VOICE)
                .addEventListeners(
                        new HelpCommand(),
                        new MessageListener(),
                        new PlayCommand(music),
                        new CommandRegistrar(commands)
                )
                .build()
                .awaitReady();

        System.out.println("Logged in as " + jda.getSelfUser().getAsTag());
    }
}
