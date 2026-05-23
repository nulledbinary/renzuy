package renzuy.commands.text;

import net.dv8tion.jda.api.events.message.MessageReceivedEvent;

/** A command invoked by a prefix-based text message (e.g. {@code !play foo}). */
public interface TextCommand {

    /** Lowercase command name, no prefix (e.g. {@code "play"}). */
    String name();

    /**
     * Executes the command. {@code args} is the raw text after the command name, already
     * trimmed; may be empty.
     */
    void execute(MessageReceivedEvent event, String args);
}
