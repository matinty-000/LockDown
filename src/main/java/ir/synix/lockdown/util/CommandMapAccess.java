package ir.synix.lockdown.util;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Retrieves the server's registered commands and resolves which plugin owns them.
 * Uses Bukkit command-map access with a reflective ownership fallback for third-party command implementations.
 */
public final class CommandMapAccess {

    private CommandMapAccess() {
    }

    /**
     * Snapshot of every known command label → resolved {@link Command} object.
     * Returns an empty map if the command map is unavailable.
     */
    public static Map<String, Command> knownCommands(Logger logger) {
        Map<String, Command> out = new HashMap<>();
        try {
            var map = Bukkit.getServer().getCommandMap();
            if (map != null) {
                out.putAll(map.getKnownCommands());
            }
        } catch (Throwable t) {
            logger.log(Level.WARNING, "[LockDown] Could not retrieve commands via API.", t);
        }
        return out;
    }

    /**
     * Best-effort owner plugin name for a command. Falls back to "Minecraft"
     * for vanilla commands and anything we cannot attribute.
     */
    public static String ownerOf(Command command, Logger logger) {
        if (command instanceof PluginCommand pc && pc.getPlugin() != null) {
            return pc.getPlugin().getName();
        }
        // Try the owning plugin via reflection if present (third-party command frameworks)
        try {
            Method getPlugin = command.getClass().getMethod("getPlugin");
            Object p = getPlugin.invoke(command);
            if (p instanceof Plugin plugin) return plugin.getName();
        } catch (NoSuchMethodException ignored) {
        } catch (Exception e) {
            logger.log(Level.FINE, "[LockDown] ownerOf() reflection failed for " + command.getName(), e);
        }
        return "Minecraft";
    }

    public static String slug(String pluginName) {
        return pluginName == null || pluginName.isBlank()
                ? "Unknown"
                : pluginName.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    public static Command lookup(String label, Logger logger) {
        if (label == null || label.isBlank()) return null;
        try {
            var map = Bukkit.getServer().getCommandMap();
            if (map != null) return map.getCommand(label);
        } catch (Throwable t) {
            logger.log(Level.FINE, "[LockDown] lookup failed for " + label, t);
        }
        return null;
    }
}
