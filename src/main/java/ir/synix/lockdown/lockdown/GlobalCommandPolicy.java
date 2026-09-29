package ir.synix.lockdown.lockdown;

import ir.synix.lockdown.rules.PatternMatcher;

import java.util.List;
import java.util.Locale;

/**
 * Evaluates configured all, blocklist, and allowlist policies during a global command lockdown.
 */
public final class GlobalCommandPolicy {

    private GlobalCommandPolicy() {
    }

    public static boolean isBlocked(String mode, List<String> entries, String command) {
        String normalizedMode = mode == null ? "all" : mode.trim().toLowerCase(Locale.ROOT);
        boolean listed = matchesConfiguredCommand(entries, command);

        return switch (normalizedMode) {
            case "blocklist", "denylist" -> listed;
            case "allowlist", "whitelist" -> !listed;
            case "all" -> true;
            default -> true;
        };
    }

    static boolean matchesConfiguredCommand(List<String> entries, String command) {
        if (entries == null || entries.isEmpty()) return false;

        String normalizedCommand = normalize(command);
        for (String entry : entries) {
            if (entry == null || entry.isBlank()) continue;

            String normalizedEntry = normalize(entry);
            if (normalizedEntry.indexOf('*') >= 0) {
                if (PatternMatcher.matches(normalizedEntry, normalizedCommand)) return true;
                continue;
            }

            if (normalizedCommand.equals(normalizedEntry)
                    || normalizedCommand.startsWith(normalizedEntry + " ")) {
                return true;
            }
        }
        return false;
    }

    private static String normalize(String command) {
        if (command == null) return "";
        String normalized = command.trim();
        if (normalized.startsWith("/")) normalized = normalized.substring(1);
        normalized = normalized.replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);

        int firstSpace = normalized.indexOf(' ');
        String first = firstSpace < 0 ? normalized : normalized.substring(0, firstSpace);
        int namespace = first.indexOf(':');
        if (namespace >= 0) {
            String tail = first.substring(namespace + 1);
            normalized = firstSpace < 0 ? tail : tail + normalized.substring(firstSpace);
        }
        return normalized;
    }
}
