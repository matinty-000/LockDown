package ir.synix.lockdown.rules;

import java.util.List;
import java.util.Locale;

/**
 * Matches normalized command strings against LockDown token patterns and wildcard rules.
 */
public final class PatternMatcher {

    private PatternMatcher() {
    }

    public static boolean matches(String pattern, String typed) {
        if (pattern == null || typed == null) return false;
        String[] p = tokenize(pattern);
        String[] t = tokenize(typed);
        return matchTokens(p, 0, t, 0);
    }

    public static boolean matchesAny(List<String> patterns, String typed) {
        if (patterns == null) return false;
        for (String p : patterns) {
            if (matches(p, typed)) return true;
        }
        return false;
    }

    private static boolean matchTokens(String[] p, int pi, String[] t, int ti) {
        if (pi == p.length) {
            return ti == t.length;
        }
        String tok = p[pi];

        if ("**".equals(tok)) {
            // greedy: consume 1+ tokens until the rest of the pattern matches
            if (ti == t.length) return false;
            for (int k = ti + 1; k <= t.length; k++) {
                if (matchTokens(p, pi + 1, t, k)) return true;
            }
            return false;
        }

        if ("*".equals(tok)) {
            if (ti == t.length) return false;
            return matchTokens(p, pi + 1, t, ti + 1);
        }

        if (ti == t.length) return false;
        if (!tok.equalsIgnoreCase(t[ti])) return false;
        return matchTokens(p, pi + 1, t, ti + 1);
    }

    private static String[] tokenize(String s) {
        String trimmed = s.trim();
        if (trimmed.isEmpty()) return new String[0];
        String[] tokens = trimmed.toLowerCase(Locale.ROOT).split("\\s+");
        if (tokens.length > 0) {
            tokens[0] = tokens[0].replaceFirst("^[a-z0-9_-]+:", "");
        }
        return tokens;
    }
}
