package renzuy.commands;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import org.jetbrains.annotations.NotNull;
import renzuy.config.PrefixStore;
import renzuy.ui.Embeds;

/**
 * {@code /prefix <new>}: admin-only. Sets a per-guild single-character text prefix.
 * Invalid input is rejected instantly; the existing prefix is left untouched.
 */
public final class PrefixCommand extends ListenerAdapter {

    public static final String NAME = "prefix";
    public static final String OPTION = "new";

    private final PrefixStore prefixes;

    public PrefixCommand(PrefixStore prefixes) {
        this.prefixes = prefixes;
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
        if (member == null || !member.hasPermission(Permission.MANAGE_SERVER)) {
            event.replyEmbeds(Embeds.warn("You need the **Manage Server** permission to change the prefix."))
                    .setEphemeral(true).queue();
            return;
        }

        OptionMapping option = event.getOption(OPTION);
        String candidate = option != null ? option.getAsString() : "";

        if (!PrefixStore.isValid(candidate)) {
            event.replyEmbeds(Embeds.error(
                    "Invalid prefix. Use **one** special character: `! @ # $ % ^ & * ? . , ; : ~ + - = < > | / \\ §`"))
                    .setEphemeral(true).queue();
            return;
        }

        prefixes.set(guild.getIdLong(), candidate);
        event.replyEmbeds(Embeds.prefixUpdated(candidate)).setEphemeral(true).queue();
    }
}
