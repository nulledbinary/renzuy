package renzuy.commands;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import org.jetbrains.annotations.NotNull;
import renzuy.commands.text.TextCommand;
import renzuy.ui.Embeds;

/**
 * {@code /info <user>} and {@code <prefix>info <id|tag|mention>}: builds a profile
 * embed for the resolved user and footers it with the bot's reply latency in
 * milliseconds. When no argument is supplied, falls back to the invoker.
 */
public final class InfoCommand extends ListenerAdapter implements TextCommand {

    public static final String NAME = "info";
    public static final String OPTION = "user";

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ROOT);

    // ---------------- Slash entry ----------------

    @Override
    public void onSlashCommandInteraction(@NotNull SlashCommandInteractionEvent event) {
        if (!event.getName().equals(NAME)) return;
        Guild guild = event.getGuild();
        if (guild == null) {
            event.replyEmbeds(Embeds.warn("This command can only be used in a server."))
                    .setEphemeral(true).queue();
            return;
        }

        long start = System.currentTimeMillis();
        OptionMapping option = event.getOption(OPTION);
        User user = option != null ? option.getAsUser() : event.getUser();

        resolveMemberAndReplySlash(event, guild, user, start);
    }

    private static void resolveMemberAndReplySlash(
            SlashCommandInteractionEvent event, Guild guild, User user, long startMillis) {
        guild.retrieveMember(user).queue(
                member -> event.replyEmbeds(buildEmbed(user, member, System.currentTimeMillis() - startMillis))
                        .setEphemeral(true).queue(),
                error -> event.replyEmbeds(buildEmbed(user, null, System.currentTimeMillis() - startMillis))
                        .setEphemeral(true).queue());
    }

    // ---------------- Text entry ----------------

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void execute(MessageReceivedEvent event, String args) {
        long start = System.currentTimeMillis();
        Guild guild = event.getGuild();
        if (args.isEmpty()) {
            User self = event.getAuthor();
            guild.retrieveMember(self).queue(
                    m -> reply(event, self, m, System.currentTimeMillis() - start),
                    err -> reply(event, self, null, System.currentTimeMillis() - start));
            return;
        }
        resolveUser(guild, args).queue(
                user -> guild.retrieveMember(user).queue(
                        m -> reply(event, user, m, System.currentTimeMillis() - start),
                        err -> reply(event, user, null, System.currentTimeMillis() - start)),
                err -> event.getChannel().sendMessageEmbeds(
                        Embeds.warn("Could not find that user. Pass a user ID, @mention, or `name#1234` tag."))
                        .queue());
    }

    private static net.dv8tion.jda.api.requests.RestAction<User> resolveUser(Guild guild, String raw) {
        String token = raw.strip();
        // <@123> or <@!123> mention
        if (token.startsWith("<@") && token.endsWith(">")) {
            String inner = token.substring(2, token.length() - 1);
            if (inner.startsWith("!")) inner = inner.substring(1);
            if (inner.chars().allMatch(Character::isDigit)) {
                return guild.getJDA().retrieveUserById(inner);
            }
        }
        // Raw snowflake id
        if (token.chars().allMatch(Character::isDigit) && token.length() >= 17) {
            return guild.getJDA().retrieveUserById(token);
        }
        // Legacy name#discrim
        int hash = token.indexOf('#');
        String username = hash < 0 ? token : token.substring(0, hash);
        Member match = guild.getMembersByName(username, true).stream().findFirst().orElse(null);
        if (match == null) {
            match = guild.getMembersByEffectiveName(username, true).stream().findFirst().orElse(null);
        }
        if (match != null) {
            return guild.getJDA().retrieveUserById(match.getIdLong());
        }
        // Last resort — let JDA fail explicitly so the error path in execute() runs.
        return guild.getJDA().retrieveUserById(token);
    }

    private static void reply(MessageReceivedEvent event, User user, Member member, long latencyMillis) {
        event.getChannel().sendMessageEmbeds(buildEmbed(user, member, latencyMillis)).queue();
    }

    // ---------------- Embed ----------------

    private static MessageEmbed buildEmbed(User user, Member member, long latencyMillis) {
        OffsetDateTime joinedDiscord = user.getTimeCreated().withOffsetSameInstant(ZoneOffset.UTC);
        OffsetDateTime joinedServer = member != null ? member.getTimeJoined().withOffsetSameInstant(ZoneOffset.UTC) : null;
        String roles = member == null || member.getRoles().isEmpty()
                ? "—"
                : member.getRoles().stream().map(r -> "<@&" + r.getId() + ">")
                        .reduce((a, b) -> a + " " + b).orElse("—");
        String status = member != null ? member.getOnlineStatus().getKey() : "unknown";

        return Embeds.userInfo(
                user.getName(),
                user.getId(),
                user.isBot(),
                user.getEffectiveAvatarUrl(),
                DATE.format(joinedDiscord),
                joinedServer != null ? DATE.format(joinedServer) : "—",
                member != null ? member.getEffectiveName() : user.getName(),
                roles,
                status,
                latencyMillis);
    }
}
