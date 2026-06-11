package renzuy.commands;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.Command;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import org.jetbrains.annotations.NotNull;
import renzuy.logging.BindStore;
import renzuy.logging.LogCategory;
import renzuy.ui.Embeds;

/**
 * {@code /bind <category>} and {@code /unbind <category>}: per-category log
 * routing, replacing the old all-in-one {@code /log} sink.
 *
 * <p>The category option autocompletes from {@link LogCategory} slugs plus the
 * special value {@code all}. Binding points the category at the channel the
 * command is run in; bindings persist via {@link BindStore}, so a restarted
 * bot keeps logging without anyone re-binding.
 */
public final class BindCommand extends ListenerAdapter {

    public static final String NAME = "bind";
    public static final String UNBIND_NAME = "unbind";
    public static final String CATEGORY_OPTION = "category";

    private static final String ALL = "all";

    private final BindStore binds;

    public BindCommand(BindStore binds) {
        this.binds = binds;
    }

    // ---------------- autocomplete ----------------

    @Override
    public void onCommandAutoCompleteInteraction(@NotNull CommandAutoCompleteInteractionEvent event) {
        boolean ours = (event.getName().equals(NAME) || event.getName().equals(UNBIND_NAME))
                && event.getFocusedOption().getName().equals(CATEGORY_OPTION);
        if (!ours) return;

        String typed = event.getFocusedOption().getValue().toLowerCase(Locale.ROOT);
        List<Command.Choice> choices = new ArrayList<>();
        if (ALL.startsWith(typed)) {
            choices.add(new Command.Choice("all — every category", ALL));
        }
        for (LogCategory category : LogCategory.values()) {
            if (choices.size() >= 25) break;
            if (category.slug().contains(typed)) {
                choices.add(new Command.Choice(category.slug() + " — " + category.description(),
                        category.slug()));
            }
        }
        event.replyChoices(choices).queue();
    }

    // ---------------- /bind and /unbind ----------------

    @Override
    public void onSlashCommandInteraction(@NotNull SlashCommandInteractionEvent event) {
        boolean binding = event.getName().equals(NAME);
        if (!binding && !event.getName().equals(UNBIND_NAME)) return;

        Guild guild = event.getGuild();
        if (guild == null) {
            event.replyEmbeds(Embeds.warn("This command can only be used in a server."))
                    .setEphemeral(true).queue();
            return;
        }
        Member member = event.getMember();
        if (!Capability.VIEW_LOGS.grantedTo(member)) {
            event.replyEmbeds(Embeds.warn("You need **View Audit Log** or **Manage Server** to use `/"
                            + event.getName() + "`."))
                    .setEphemeral(true).queue();
            return;
        }
        if (binding && !event.getChannel().getType().isMessage()) {
            event.replyEmbeds(Embeds.error("Run `/bind` in a normal text channel — that's where the logs will be posted."))
                    .setEphemeral(true).queue();
            return;
        }

        OptionMapping option = event.getOption(CATEGORY_OPTION);
        String raw = option == null ? "" : option.getAsString().strip().toLowerCase(Locale.ROOT);

        if (ALL.equals(raw)) {
            if (binding) {
                bindAll(event, guild);
            } else {
                unbindAll(event, guild);
            }
            return;
        }

        LogCategory category = LogCategory.fromSlug(raw).orElse(null);
        if (category == null) {
            event.replyEmbeds(Embeds.error("Unknown category `" + Embeds.escape(raw)
                            + "` — pick one from the autocomplete list."))
                    .setEphemeral(true).queue();
            return;
        }

        if (binding) {
            long channelId = event.getChannelIdLong();
            Long previous = binds.bind(guild.getIdLong(), category, channelId);
            String body;
            if (previous == null) {
                body = "**`" + category.slug() + "` logging enabled here.** " + category.description() + ".";
            } else if (previous == channelId) {
                body = "`" + category.slug() + "` is already bound to this channel.";
            } else {
                body = "Moved `" + category.slug() + "` logging here from <#" + previous + ">.";
            }
            event.replyEmbeds(Embeds.info(body)).setEphemeral(true).queue();
        } else {
            boolean removed = binds.unbind(guild.getIdLong(), category);
            event.replyEmbeds(removed
                            ? Embeds.success("`" + category.slug() + "` logging unbound.")
                            : Embeds.warn("`" + category.slug() + "` was not bound to any channel."))
                    .setEphemeral(true).queue();
        }
    }

    private void bindAll(SlashCommandInteractionEvent event, Guild guild) {
        long channelId = event.getChannelIdLong();
        for (LogCategory category : LogCategory.values()) {
            binds.bind(guild.getIdLong(), category, channelId);
        }
        event.replyEmbeds(Embeds.info("**All " + LogCategory.values().length
                        + " log categories are now bound to this channel.**"))
                .setEphemeral(true).queue();
    }

    private void unbindAll(SlashCommandInteractionEvent event, Guild guild) {
        int removed = 0;
        Map<LogCategory, Long> bound = binds.all(guild.getIdLong());
        for (LogCategory category : bound.keySet()) {
            if (binds.unbind(guild.getIdLong(), category)) removed++;
        }
        event.replyEmbeds(removed == 0
                        ? Embeds.warn("Nothing was bound.")
                        : Embeds.success("Unbound " + removed + " log categor" + (removed == 1 ? "y" : "ies") + "."))
                .setEphemeral(true).queue();
    }
}
