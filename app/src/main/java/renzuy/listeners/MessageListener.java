package renzuy.listeners;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.Message.MentionType;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.jetbrains.annotations.NotNull;

public final class MessageListener extends ListenerAdapter {

    private static final List<String> PROTECTED_IDS = List.of("1506888836481810534");

    private static final List<String> TAG_SCOLD_REPLIES = List.of(
            "Tang ina mo wag mo i-tag 'yan, %s",
            "Bobo ka ba? Busy 'yan, %s",
            "Tarantado ka masyado, %s",
            "Tanga amputa, wala ka bang magawa sa buhay mo at ipiping mo 'yan? %s",
            "What the fuck are you doing tagging him? %s",
            "Masyado kang bobo %s"
    );

    private record Rule(Pattern trigger, String replyTemplate) {}

    private static final List<Rule> RULES = List.of(
            rule("\\bmukh?a\\s+kang\\s+burat\\b", "Mas mukha kang burat %s"),
            rule("\\btang\\s+ina\\s+mo\\b", "Tang ina mo rin %s"),
            rule("\\bbobo\\s+mo\\b", "Mas bobo ka %s"),
            rule("\\bpa-kiss\\s+nga\\b", "Muah sa pwet 'to %s"),
            rule("\\btanga\\s+mo\\b", "Tingin ka sa salamin %s")
    );

    private static Rule rule(String regex, String replyTemplate) {
        return new Rule(Pattern.compile(regex, Pattern.CASE_INSENSITIVE), replyTemplate);
    }

    @Override
    public void onMessageReceived(@NotNull MessageReceivedEvent event) {
        if (event.getAuthor().isBot()) {
            return;
        }

        Message message = event.getMessage();
        String authorMention = event.getAuthor().getAsMention();

        if (scoldsForTaggingProtectedUser(event, message, authorMention)) {
            return;
        }

        if (!message.getMentions().isMentioned(event.getJDA().getSelfUser(), MentionType.USER)) {
            return;
        }

        String content = message.getContentRaw();
        for (Rule rule : RULES) {
            if (rule.trigger().matcher(content).find()) {
                message.reply(String.format(rule.replyTemplate(), authorMention))
                        .mentionRepliedUser(false)
                        .queue();
                return;
            }
        }
    }

    /**
     * If the message mentions the protected user (and was not sent by that user),
     * replies with a random scolding line and returns {@code true}.
     */
    private boolean scoldsForTaggingProtectedUser(MessageReceivedEvent event, Message message, String authorMention) {

        boolean taggedProtectedUser = message.getMentions().getUsers().stream()
                .map(User::getId)
                .anyMatch(PROTECTED_IDS::contains);

        if (!taggedProtectedUser) {
            return false;
        }
        
        String template = TAG_SCOLD_REPLIES.get(ThreadLocalRandom.current().nextInt(TAG_SCOLD_REPLIES.size()));
        message.reply(String.format(template, authorMention))
                .mentionRepliedUser(false)
                .queue();
        return true;
    }
}
