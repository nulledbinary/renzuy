package renzuy.ui;

import java.awt.Color;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;

/**
 * One-stop builder for the Jockie-style coloured-stripe embeds every command reply uses.
 * Every embed is meant to be sent ephemerally (setEphemeral(true)).
 */
public final class Embeds {

    public static final Color PLAYING = new Color(0x1DB954); // Spotify green
    public static final Color QUEUED  = new Color(0x5865F2); // Discord blurple
    public static final Color INFO    = new Color(0x3498DB); // info blue
    public static final Color SUCCESS = new Color(0x57F287); // Discord green
    public static final Color WARN    = new Color(0xFEE75C); // Discord yellow
    public static final Color ERROR   = new Color(0xED4245); // Discord red

    private static final String PLAY_ICON =
            "https://cdn.jsdelivr.net/gh/twitter/twemoji@14.0.2/assets/72x72/25b6.png";
    private static final String QUEUE_ICON =
            "https://cdn.jsdelivr.net/gh/twitter/twemoji@14.0.2/assets/72x72/1f4cb.png";
    private static final String STOP_ICON =
            "https://cdn.jsdelivr.net/gh/twitter/twemoji@14.0.2/assets/72x72/23f9.png";
    private static final String SKIP_ICON =
            "https://cdn.jsdelivr.net/gh/twitter/twemoji@14.0.2/assets/72x72/23ed.png";
    private static final String WAVE_ICON =
            "https://cdn.jsdelivr.net/gh/twitter/twemoji@14.0.2/assets/72x72/1f44b.png";

    private Embeds() {}

    public static MessageEmbed playing(String title) {
        return new EmbedBuilder()
                .setColor(PLAYING)
                .setAuthor("Started playing", null, PLAY_ICON)
                .setDescription("**" + escape(title) + "**")
                .build();
    }

    public static MessageEmbed queued(String title, int position) {
        return new EmbedBuilder()
                .setColor(QUEUED)
                .setAuthor("Queued", null, QUEUE_ICON)
                .setDescription("**" + escape(title) + "**")
                .setFooter("Position #" + position)
                .build();
    }

    /**
     * Reply for a playlist: announces the playlist as a whole, names the first track
     * (which is either now playing or queued at {@code firstPosition}), and reports
     * how many followed it onto the queue.
     */
    public static MessageEmbed playlistQueued(
            String playlistTitle, int totalTracks, String firstTrackTitle,
            boolean startedNow, int firstPosition) {
        String header = startedNow ? "Started playing playlist" : "Queued playlist";
        EmbedBuilder b = new EmbedBuilder()
                .setColor(startedNow ? PLAYING : QUEUED)
                .setAuthor(header, null, QUEUE_ICON)
                .setDescription("**" + escape(playlistTitle == null ? "Playlist" : playlistTitle) + "**\n"
                        + totalTracks + " track" + (totalTracks == 1 ? "" : "s"));
        String firstLabel = startedNow ? "Now playing" : "First track";
        b.addField(firstLabel, "**" + escape(firstTrackTitle) + "**", false);
        if (!startedNow) {
            b.setFooter("First at position #" + firstPosition);
        } else if (totalTracks > 1) {
            b.setFooter((totalTracks - 1) + " queued behind it");
        }
        return b.build();
    }

    public static MessageEmbed skipped(String skipped, String nextUp) {
        EmbedBuilder b = new EmbedBuilder()
                .setColor(INFO)
                .setAuthor("Skipped", null, SKIP_ICON)
                .setDescription("**" + escape(skipped) + "**");
        if (nextUp != null) {
            b.addField("Now playing", "**" + escape(nextUp) + "**", false);
        }
        return b.build();
    }

    public static MessageEmbed stopped() {
        return new EmbedBuilder()
                .setColor(INFO)
                .setAuthor("Stopped playback", null, STOP_ICON)
                .setDescription("Cleared the queue and left the channel.")
                .build();
    }

    public static MessageEmbed removed(String title, int position) {
        return new EmbedBuilder()
                .setColor(SUCCESS)
                .setAuthor("Removed from queue", null, QUEUE_ICON)
                .setDescription("`" + position + ".` **" + escape(title) + "**")
                .build();
    }

    public static MessageEmbed info(String message) {
        return new EmbedBuilder().setColor(INFO).setDescription(message).build();
    }

    public static MessageEmbed warn(String message) {
        return new EmbedBuilder().setColor(WARN).setDescription(message).build();
    }

    public static MessageEmbed error(String message) {
        return new EmbedBuilder().setColor(ERROR).setDescription(message).build();
    }

    public static MessageEmbed leaving(String reason, String hint) {
        EmbedBuilder b = new EmbedBuilder()
                .setColor(ERROR)
                .setAuthor("Leaving", null, WAVE_ICON)
                .setDescription(reason);
        if (hint != null) {
            b.setFooter(hint);
        }
        return b.build();
    }

    /** Escape Discord markdown so titles with * or _ render literally. */
    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("*", "\\*")
                .replace("_", "\\_")
                .replace("`", "\\`")
                .replace("~", "\\~");
    }
}
