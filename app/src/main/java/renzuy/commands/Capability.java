package renzuy.commands;

import java.util.EnumSet;
import java.util.Set;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Member;

/**
 * Coarse capability tiers used to gate slash commands and to filter the
 * {@code /help} listing by what the invoking member can actually use.
 *
 * <p>Each capability maps to a Discord {@link Permission} (or a small set of
 * them, in which case ANY one grants the capability). {@link #grantedTo(Member)}
 * is the single source of truth — both runtime permission checks and the
 * help-listing filter call through it, so the user never sees a command
 * suggested that they can't actually run.
 */
public enum Capability {

    EVERYONE(),
    MANAGE_PREFIX(Permission.MANAGE_SERVER),
    PURGE_MESSAGES(Permission.MESSAGE_MANAGE),
    TIMEOUT_MEMBERS(Permission.MODERATE_MEMBERS),
    BAN_MEMBERS(Permission.BAN_MEMBERS),
    VIEW_LOGS(Permission.VIEW_AUDIT_LOGS, Permission.MANAGE_SERVER);

    private final Set<Permission> any;

    Capability(Permission... permissions) {
        this.any = permissions.length == 0 ? EnumSet.noneOf(Permission.class) : EnumSet.copyOf(java.util.Arrays.asList(permissions));
    }

    public boolean grantedTo(Member member) {
        if (any.isEmpty()) return true;
        if (member == null) return false;
        if (member.hasPermission(Permission.ADMINISTRATOR)) return true;
        for (Permission p : any) {
            if (member.hasPermission(p)) return true;
        }
        return false;
    }

    /** The first underlying permission, for error messages. {@code null} for EVERYONE. */
    public Permission representativePermission() {
        return any.isEmpty() ? null : any.iterator().next();
    }
}
