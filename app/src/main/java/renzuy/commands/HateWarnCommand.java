package renzuy.commands;

import java.time.Duration;
import java.util.List;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.Command;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import org.jetbrains.annotations.NotNull;
import renzuy.commands.moderation.DurationParser;
import renzuy.moderation.HateWarnConfig;
import renzuy.moderation.HateWarnConfig.Policy;
import renzuy.moderation.HateWarnConfig.PunishmentType;
import renzuy.ui.Embeds;

/**
 * {@code /hatewarn <count> <punishment>}: configures the per-guild warning
 * threshold and the punishment applied when the user crosses it.
 *
 * <p>The {@code punishment} option uses autocomplete; the value is a short
 * directive of the form {@code mute <duration>} or {@code ban <duration>}
 * (e.g. {@code mute 30m}, {@code ban 7d}). Autocomplete offers common presets
 * but free-form input is accepted as long as it parses.
 */
public final class HateWarnCommand extends ListenerAdapter {

    public static final String NAME = "hatewarn";
    public static final String COUNT_OPTION = "count";
    public static final String PUNISHMENT_OPTION = "punishment";

    private static final List<String> PRESETS = List.of(
            "mute 5m", "mute 30m", "mute 2h", "mute 1d",
            "ban 1h", "ban 1d", "ban 7d", "ban 30d");

    private final HateWarnConfig config;

    public HateWarnCommand(HateWarnConfig config) {
        this.config = config;
    }

    @Override
    public void onCommandAutoCompleteInteraction(@NotNull CommandAutoCompleteInteractionEvent event) {
        if (!event.getName().equals(NAME)) return;
        if (!PUNISHMENT_OPTION.equals(event.getFocusedOption().getName())) return;
        String typed = event.getFocusedOption().getValue().toLowerCase().strip();
        List<Command.Choice> choices = PRESETS.stream()
                .filter(p -> typed.isEmpty() || p.startsWith(typed))
                .limit(25)
                .map(p -> new Command.Choice(p, p))
                .toList();
        event.replyChoices(choices).queue();
    }

    @Override
    public void onSlashCommandInteraction(@NotNull SlashCommandInteractionEvent event) {
        if (!event.getName().equals(NAME)) return;
        Guild guild = event.getGuild();
        if (guild == null) {
            event.replyEmbeds(Embeds.warn("This command can only be used in a server."))
                    .setEphemeral(true).queue();
            return;
        }
        Member member = event.getMember();
        if (member == null || (!member.hasPermission(net.dv8tion.jda.api.Permission.MODERATE_MEMBERS)
                && !member.hasPermission(net.dv8tion.jda.api.Permission.ADMINISTRATOR))) {
            event.replyEmbeds(Embeds.warn("You need the **Timeout Members** permission to use `/hatewarn`."))
                    .setEphemeral(true).queue();
            return;
        }

        OptionMapping countOpt = event.getOption(COUNT_OPTION);
        OptionMapping punOpt = event.getOption(PUNISHMENT_OPTION);
        if (countOpt == null || punOpt == null) {
            event.replyEmbeds(Embeds.error("Usage: `/hatewarn <count> <punishment>` — punishment like `mute 30m` or `ban 7d`."))
                    .setEphemeral(true).queue();
            return;
        }
        int count = (int) countOpt.getAsLong();
        if (count < 1 || count > 20) {
            event.replyEmbeds(Embeds.error("Warning count must be between 1 and 20."))
                    .setEphemeral(true).queue();
            return;
        }

        String raw = punOpt.getAsString().strip().toLowerCase();
        int space = raw.indexOf(' ');
        if (space < 0) {
            event.replyEmbeds(Embeds.error("Punishment needs both a type and a duration, e.g. `mute 30m` or `ban 7d`."))
                    .setEphemeral(true).queue();
            return;
        }
        String typeWord = raw.substring(0, space).strip();
        String durationWord = raw.substring(space + 1).strip();

        PunishmentType type;
        if (typeWord.startsWith("mute") || typeWord.startsWith("tempmute")) {
            type = PunishmentType.TEMPMUTE;
        } else if (typeWord.startsWith("ban") || typeWord.startsWith("tempban")) {
            type = PunishmentType.TEMPBAN;
        } else {
            event.replyEmbeds(Embeds.error("Punishment type must be `mute` or `ban`."))
                    .setEphemeral(true).queue();
            return;
        }

        Duration duration;
        try {
            duration = DurationParser.parseOrThrow(durationWord);
        } catch (DurationParser.InvalidDurationException e) {
            event.replyEmbeds(Embeds.error(e.getMessage())).setEphemeral(true).queue();
            return;
        }
        if (type == PunishmentType.TEMPMUTE && duration.compareTo(Duration.ofDays(28)) > 0) {
            event.replyEmbeds(Embeds.error("Mute duration cannot exceed 28 days (Discord limit)."))
                    .setEphemeral(true).queue();
            return;
        }

        Policy policy = new Policy(count, type, duration);
        config.set(guild.getIdLong(), policy);

        String body = "Set: after **" + count + "** warning"
                + (count == 1 ? "" : "s")
                + " → **" + type.name().toLowerCase() + " " + DurationParser.humanize(duration) + "**.";
        event.replyEmbeds(Embeds.info(body)).setEphemeral(true).queue();
    }
}
