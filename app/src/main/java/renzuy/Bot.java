package renzuy;

import club.minnced.discord.jdave.interop.JDaveSessionFactory;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.OnlineStatus;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.audio.AudioModuleConfig;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.MemberCachePolicy;
import net.dv8tion.jda.api.utils.cache.CacheFlag;
import renzuy.audio.MusicService;
import renzuy.commands.AfkCommand;
import renzuy.commands.BindCommand;
import renzuy.commands.CommandRegistrar;
import renzuy.commands.ConfessCommand;
import renzuy.commands.CountCommand;
import renzuy.commands.HateWarnCommand;
import renzuy.commands.HelpCommand;
import renzuy.commands.InfoCommand;
import renzuy.commands.LockdownCommand;
import renzuy.commands.PlayCommand;
import renzuy.commands.PrefixCommand;
import renzuy.commands.PurgeCommand;
import renzuy.commands.QueueCommand;
import renzuy.commands.RemoveCommand;
import renzuy.commands.SkipCommand;
import renzuy.commands.StopCommand;
import renzuy.commands.TambayCommand;
import renzuy.commands.TempBanCommand;
import renzuy.commands.TempMuteCommand;
import renzuy.commands.moderation.UnbanScheduler;
import renzuy.commands.text.TextCommandRouter;
import renzuy.config.PrefixStore;
import renzuy.confession.ConfessionAudit;
import renzuy.counting.CountingStore;
import renzuy.logging.BindStore;
import renzuy.logging.ServerLogger;
import renzuy.moderation.AutoModerator;
import renzuy.moderation.HateWarnConfig;
import renzuy.moderation.RaidGuard;
import renzuy.store.StateStore;
import renzuy.store.StateStores;

public final class Bot {

    public static void main(String[] args) throws InterruptedException {
        // Must be set before any network classes are initialized or loaded by JDA.
        System.setProperty("jdk.http.auth.tunneling.disabledSchemes", "");

        String token = DotEnv.get("DISCORD_TOKEN");
        if (token == null || token.isBlank()) {
            System.err.println("DISCORD_TOKEN is not set. Put it in a .env file at the project root, "
                    + "or set it as an environment variable.");
            System.exit(1);
        }

        MusicService music = new MusicService();
        PrefixStore prefixes = PrefixStore.defaultLocation();
        UnbanScheduler unbanScheduler = new UnbanScheduler();
        HateWarnConfig hateWarnConfig = HateWarnConfig.defaultLocation();
        StateStore stateStore = StateStores.fromEnv();
        CountingStore countingStore = new CountingStore(stateStore);
        BindStore bindStore = new BindStore(stateStore);
        ConfessionAudit confessionAudit = new ConfessionAudit(stateStore, bindStore);

        HelpCommand    helpCommand    = new HelpCommand(prefixes);
        PlayCommand    playCommand    = new PlayCommand(music);
        StopCommand    stopCommand    = new StopCommand(music);
        SkipCommand    skipCommand    = new SkipCommand(music);
        QueueCommand   queueCommand   = new QueueCommand(music);
        RemoveCommand  removeCommand  = new RemoveCommand(music);
        InfoCommand    infoCommand    = new InfoCommand();
        PrefixCommand  prefixCommand  = new PrefixCommand(prefixes);
        PurgeCommand   purgeCommand   = new PurgeCommand();
        TempMuteCommand tempMuteCommand = new TempMuteCommand();
        TempBanCommand  tempBanCommand  = new TempBanCommand(unbanScheduler);
        BindCommand     bindCommand     = new BindCommand(bindStore);
        ServerLogger    serverLogger    = new ServerLogger(bindStore);
        LockdownCommand lockdownCommand = new LockdownCommand(stateStore);
        AfkCommand      afkCommand      = new AfkCommand();
        HateWarnCommand hateWarnCommand = new HateWarnCommand(hateWarnConfig);
        AutoModerator   autoModerator   = new AutoModerator(hateWarnConfig, unbanScheduler);
        RaidGuard       raidGuard       = new RaidGuard();
        TambayCommand   tambayCommand   = new TambayCommand();
        ConfessCommand  confessCommand  = new ConfessCommand(confessionAudit);
        CountCommand    countCommand    = new CountCommand(countingStore);

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
                Commands.slash(InfoCommand.NAME, "Show detailed account / server info for a user")
                        .addOption(OptionType.USER, InfoCommand.OPTION,
                                "The user to look up — defaults to you", false),
                Commands.slash(PrefixCommand.NAME, "Admin: set the text-command prefix (single special character)")
                        .addOption(OptionType.STRING, PrefixCommand.OPTION,
                                "A single special character, e.g. ! @ # $ % ^ & §", true)
                        .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER)),
                Commands.slash(AfkCommand.NAME, "Mark yourself AFK — reason may include an image/GIF URL")
                        .addOption(OptionType.STRING, AfkCommand.REASON_OPTION,
                                "Reason (optional). Paste an image/GIF URL to display it.", false),
                Commands.slash(TambayCommand.NAME, "Join the voice channel to idle and chill")
                        .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER)),
                Commands.slash(ConfessCommand.NAME, "Share an anonymous confession via a private form"),
                Commands.slash(CountCommand.NAME, "Admin: bind this channel as the counting-game channel")
                        .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER)),

                // ----- moderation -----
                Commands.slash(PurgeCommand.NAME, "Bulk-delete up to 100 recent messages from this channel")
                        .addOptions(new OptionData(OptionType.INTEGER, PurgeCommand.COUNT_OPTION,
                                "How many messages to delete (1–100)", true)
                                .setMinValue(1).setMaxValue(100))
                        .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MESSAGE_MANAGE)),
                Commands.slash(TempMuteCommand.NAME, "Time a user out for a duration (e.g. 30s, 15m, 2h, 7d)")
                        .addOption(OptionType.USER,   TempMuteCommand.USER_OPTION,     "Who to time out", true)
                        .addOption(OptionType.STRING, TempMuteCommand.DURATION_OPTION, "Duration: e.g. 30s, 15m, 2h, 7d (max 28d)", true)
                        .addOption(OptionType.STRING, TempMuteCommand.REASON_OPTION,   "Reason (audit log)", false)
                        .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MODERATE_MEMBERS)),
                Commands.slash(TempBanCommand.NAME, "Ban a user and schedule auto-unban after a duration")
                        .addOption(OptionType.USER,   TempBanCommand.USER_OPTION,     "Who to ban", true)
                        .addOption(OptionType.STRING, TempBanCommand.DURATION_OPTION, "Duration: e.g. 30m, 2h, 7d", true)
                        .addOption(OptionType.STRING, TempBanCommand.REASON_OPTION,   "Reason (audit log)", false)
                        .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.BAN_MEMBERS)),
                Commands.slash(BindCommand.NAME, "Bind a log category (confession, nickname, …) to this channel")
                        .addOptions(new OptionData(OptionType.STRING, BindCommand.CATEGORY_OPTION,
                                "Which events to log here — or `all`", true)
                                .setAutoComplete(true))
                        .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.VIEW_AUDIT_LOGS)),
                Commands.slash(BindCommand.UNBIND_NAME, "Stop routing a log category")
                        .addOptions(new OptionData(OptionType.STRING, BindCommand.CATEGORY_OPTION,
                                "Which category to unbind — or `all`", true)
                                .setAutoComplete(true))
                        .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.VIEW_AUDIT_LOGS)),
                Commands.slash(LockdownCommand.NAME, "Admin: toggle a full lockdown of this channel (reason via form)")
                        .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.ADMINISTRATOR)),
                Commands.slash(HateWarnCommand.NAME, "Configure auto-punishment after N hate-speech warnings")
                        .addOptions(new OptionData(OptionType.INTEGER, HateWarnCommand.COUNT_OPTION,
                                "Warnings before punishment (1–20)", true)
                                .setMinValue(1).setMaxValue(20))
                        .addOptions(new OptionData(OptionType.STRING, HateWarnCommand.PUNISHMENT_OPTION,
                                "Punishment — e.g. `mute 30m` or `ban 7d`", true)
                                .setAutoComplete(true))
                        .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MODERATE_MEMBERS))
        };

        TextCommandRouter router = new TextCommandRouter(prefixes,
                helpCommand, playCommand, stopCommand, skipCommand,
                queueCommand, removeCommand, infoCommand, purgeCommand, afkCommand);

        JDA jda = JDABuilder.createLight(token,
                        GatewayIntent.GUILD_MESSAGES,
                        GatewayIntent.MESSAGE_CONTENT,
                        GatewayIntent.GUILD_VOICE_STATES,
                        GatewayIntent.GUILD_MEMBERS,
                        GatewayIntent.GUILD_MODERATION)
                .setAudioModuleConfig(new AudioModuleConfig()
                        .withDaveSessionFactory(new JDaveSessionFactory()))
                .enableCache(CacheFlag.VOICE_STATE)
                .setMemberCachePolicy(MemberCachePolicy.VOICE)
                .setStatus(OnlineStatus.IDLE)
                .setActivity(Activity.customStatus("Booting…"))
                .addEventListeners(
                        helpCommand, playCommand, stopCommand, skipCommand,
                        queueCommand, removeCommand, infoCommand, prefixCommand,
                        purgeCommand, tempMuteCommand, tempBanCommand,
                        bindCommand, serverLogger, lockdownCommand, confessionAudit,
                        afkCommand, hateWarnCommand, autoModerator, raidGuard,
                        tambayCommand, confessCommand, countCommand, router,
                        new CommandRegistrar(commands))
                .build()
                .awaitReady();

        System.out.println("Logged in as " + jda.getSelfUser().getAsTag());

        PresenceUpdater presence = new PresenceUpdater(jda);
        presence.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("[Shutdown] Stopping presence updater and JDA");
            presence.shutdown();
            unbanScheduler.shutdown();
            jda.shutdown();
        }, "shutdown-hook"));
    }
}
