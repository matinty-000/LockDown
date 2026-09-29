package ir.synix.lockdown.command;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;

/**
 * Resolves whether a command sender should be treated as authorized for a matched command rule.
 */
public final class PermissionResolver {

    private PermissionResolver() {
    }

    public static boolean isAuthorized(CommandSender sender, Command command) {
        if (sender == null) return false;
        if (sender instanceof ConsoleCommandSender) return true;
        if (sender.isOp()) return true;
        if (command == null) {
            return false;
        }
        try {
            return command.testPermissionSilent(sender);
        } catch (Throwable t) {
            String perm = command.getPermission();
            if (perm != null && !perm.isEmpty()) {
                return sender.hasPermission(perm);
            }
            return true;
        }
    }

    public static boolean isFullyAuthorized(CommandSender sender, Command command) {
        return isAuthorized(sender, command);
    }
}
