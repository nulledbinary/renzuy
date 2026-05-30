package renzuy;

import java.text.NumberFormat;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.OnlineStatus;
import net.dv8tion.jda.api.entities.Activity;

/**
 * Drives the bot's presence (status text + online/dnd icon).
 *
 * <p>Behaviour is driven by the {@code APP_ENV} environment variable:
 * <ul>
 *   <li><b>production</b> → status is "Inaalipin ang mga &lt;total guild members&gt;",
 *       refreshed every {@link #REFRESH_INTERVAL_MINUTES} minutes;</li>
 *   <li>anything else (dev / local) → static "Undergoing Rough Maintenance" with DND.</li>
 * </ul>
 */
public final class PresenceUpdater {

    private static final int REFRESH_INTERVAL_MINUTES = 5;
    private static final NumberFormat NUMBER_FORMAT = NumberFormat.getInstance(Locale.US);

    private final JDA jda;
    private final boolean production;
    private ScheduledExecutorService scheduler;

    public PresenceUpdater(JDA jda) {
        this.jda = jda;
        this.production = isProduction();
    }

    public void start() {
        if (production) {
            updateProductionStatus();
            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "presence-updater");
                t.setDaemon(true);
                return t;
            });
            scheduler.scheduleAtFixedRate(
                    this::updateProductionStatus,
                    REFRESH_INTERVAL_MINUTES,
                    REFRESH_INTERVAL_MINUTES,
                    TimeUnit.MINUTES);
            System.out.println("[Presence] Production mode — status refreshes every "
                    + REFRESH_INTERVAL_MINUTES + " min");
        } else {
            jda.getPresence().setPresence(OnlineStatus.IDLE,
                    Activity.customStatus("Undergoing Rough Maintenance"));
            System.out.println("[Presence] Development mode — status set to 'Undergoing Rough Maintenance'");
        }
    }

    public void shutdown() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    private void updateProductionStatus() {
        try {
            long total = jda.getGuilds().stream()
                    .mapToLong(g -> g.getMemberCount())
                    .sum();
            String text;
            if (renzuy.commands.TambayCommand.isActive) {
                text = "I am just chilling on this Voice Channel - All in while monitoring " + NUMBER_FORMAT.format(total) + " people";
            } else {
                text = "Inaalipin ng mga " + NUMBER_FORMAT.format(total) + " Kuneho";
            }
            jda.getPresence().setPresence(OnlineStatus.IDLE, Activity.customStatus(text));
        } catch (Exception e) {
            System.err.println("[Presence] Failed to refresh status: " + e.getMessage());
        }
    }

    private static boolean isProduction() {
        String env = DotEnv.get("APP_ENV");
        return env != null && env.equalsIgnoreCase("production");
    }
}
