package renzuy.commands;

import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.GuildVoiceState;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.jetbrains.annotations.NotNull;
import renzuy.ui.Embeds;

public final class TambayCommand extends ListenerAdapter {

    public static final String NAME = "tambay";

    public static volatile boolean isActive = false;

    @Override
    public void onSlashCommandInteraction(@NotNull SlashCommandInteractionEvent event) {
        if (!event.getName().equals(NAME))
            return;

        Guild guild = event.getGuild();
        if (guild == null) {
            event.replyEmbeds(Embeds.warn("This command can only be used in a server."))
                    .setEphemeral(true).queue();
            return;
        }

        Member member = event.getMember();
        GuildVoiceState state = member != null ? member.getVoiceState() : null;
        AudioChannel voice = state != null ? state.getChannel() : null;

        if (voice == null) {
            event.replyEmbeds(Embeds.warn("Join a voice channel first.")).setEphemeral(true).queue();
            return;
        }

        if (!Capability.MANAGE_SERVER.grantedTo(member)) {
            event.replyEmbeds(Embeds.warn("You need the **Manage Server** permission to use `/tambay`.")).setEphemeral(true).queue();
            return;
        }

        try {
            guild.getAudioManager().openAudioConnection(voice);
            isActive = true;

            long total = event.getJDA().getGuilds().stream().mapToLong(g -> g.getMemberCount()).sum();
            java.text.NumberFormat format = java.text.NumberFormat.getInstance(java.util.Locale.US);
            String text = "I am just chilling on this Voice Channel - All in while monitoring " + format.format(total)
                    + " people";
            event.getJDA().getPresence().setPresence(net.dv8tion.jda.api.OnlineStatus.IDLE,
                    net.dv8tion.jda.api.entities.Activity.customStatus(text));

            event.replyEmbeds(Embeds.success("Joined " + voice.getAsMention() + " to chill."))
                    .setEphemeral(false).queue();
        } catch (Exception e) {
            event.replyEmbeds(Embeds.error("Could not join voice channel: " + e.getMessage()))
                    .setEphemeral(true).queue();
        }
    }
}
