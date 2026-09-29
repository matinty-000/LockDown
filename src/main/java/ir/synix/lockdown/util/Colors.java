package ir.synix.lockdown.util;

import net.md_5.bungee.api.ChatColor;
import org.bukkit.ChatColor;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Colour + placeholder helpers shared across the plugin.
 * Supports legacy codes (&) and modern hex colors (&#FFFFFF).
 */
public final class Colors {

    private static final Pattern HEX_PATTERN = Pattern.compile("&#([a-fA-F0-9]{6})");
    private static final Pattern PLACEHOLDER = Pattern.compile("%([a-zA-Z0-9_]+)%");

    private Colors() {
    }

    /** Translate legacy & codes and modern &#FFFFFF hex colors */
    public static String colorize(String input) {
        if (input == null) return "";

        // Translate hex colors: &#123456 -> Spigot/Bungee ChatColor representation
        Matcher matcher = HEX_PATTERN.matcher(input);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String hex = matcher.group(1);
            try {
                // Use Bungee ChatColor for hex support (built into Spigot/Paper)
                matcher.appendReplacement(sb, ChatColor.of("#" + hex).toString());
            } catch (Throwable t) {
                // Fallback on legacy if Bungee ChatColor fails
                matcher.appendReplacement(sb, "#" + hex);
            }
        }
        matcher.appendTail(sb);

        return ChatColor.translateAlternateColorCodes('&', sb.toString());
    }

    public static String colorize(String input, Object... kv) {
        return colorize(replace(input, kv));
    }

    /** Strip colours from a string (used when forwarding to Discord/email). */
    public static String strip(String input) {
        return input == null ? "" : ChatColor.stripColor(colorize(input));
    }

    /**
     * Replace {@code %name%} placeholders. Pass an even number of arguments as
     * name/value pairs.
     */
    public static String replace(String input, Object... kv) {
        if (input == null || kv.length == 0) return input;
        if (kv.length % 2 != 0) {
            throw new IllegalArgumentException("replace() requires an even number of name/value args");
        }
        String out = input;
        for (int i = 0; i < kv.length; i += 2) {
            String key = "%" + kv[i] + "%";
            String val = kv[i + 1] == null ? "" : kv[i + 1].toString();
            out = out.replace(key, val);
        }
        return out;
    }

    /** Convenience: substitute from a single varargs map. */
    public static String replaceMap(String input, Map<String, ?> vars) {
        if (input == null || vars == null || vars.isEmpty()) return input;
        Matcher m = PLACEHOLDER.matcher(input);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            Object v = vars.get(m.group(1));
            m.appendReplacement(sb, Matcher.quoteReplacement(v == null ? m.group(0) : v.toString()));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
