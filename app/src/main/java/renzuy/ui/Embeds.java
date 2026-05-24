package renzuy.ui;

import java.awt.Color;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;

/** Embed builders for every reply the bot produces. */
public final class Embeds {

    public static final Color PLAYING = new Color(0x1DB954);
    public static final Color QUEUED  = new Color(0x5865F2);
    public static final Color INFO    = new Color(0x3498DB);
    public static final Color SUCCESS = new Color(0x57F287);
    public static final Color WARN    = new Color(0xFEE75C);
    public static final Color ERROR   = new Color(0xED4245);

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
    private static final String GEAR_ICON =
            "https://cdn.jsdelivr.net/gh/twitter/twemoji@14.0.2/assets/72x72/2699.png";
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

    public static MessageEmbed playlistQueued(
            String playlistTitle, int totalTracks, String firstTrackTitle,
            boolean startedNow, int firstPosition) {
        String header = startedNow ? "Started playing playlist" : "Queued playlist";
        EmbedBuilder b = new EmbedBuilder()
                .setColor(startedNow ? PLAYING : QUEUED)
                .setAuthor(header, null, QUEUE_ICON)
                .setDescription("**" + escape(playlistTitle == null ? "Playlist" : playlistTitle) + "**\n"
                        + totalTracks + " track" + (totalTracks == 1 ? "" : "s"));
        b.addField(startedNow ? "Now playing" : "First track",
                "**" + escape(firstTrackTitle) + "**", false);
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

    public static MessageEmbed prefixUpdated(String newPrefix) {
        return new EmbedBuilder()
                .setColor(SUCCESS)
                .setAuthor("Prefix updated", null, GEAR_ICON)
                .setDescription("Text commands now respond to `" + newPrefix + "`.")
                .build();
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("*", "\\*")
                .replace("_", "\\_")
                .replace("`", "\\`")
                .replace("~", "\\~");
    }
}
