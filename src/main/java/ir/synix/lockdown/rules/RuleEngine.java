package ir.synix.lockdown.rules;

import ir.synix.lockdown.LockDownPlugin;
import ir.synix.lockdown.config.ConfigManager;
import ir.synix.lockdown.rules.model.Rule;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Loads manual and generated rules and resolves the first enabled rule matching a command.
 */
public final class RuleEngine {

    private final LockDownPlugin plugin;
    private final ConfigManager config;

    private volatile CopyOnWriteArrayList<Rule> rules = new CopyOnWriteArrayList<>();

    // Cached enabled rules to prevent scanning 175+ rules on every keystroke
    private volatile List<Rule> enabledRules = new ArrayList<>();

    public RuleEngine(LockDownPlugin plugin, ConfigManager config) {
        this.plugin = plugin;
        this.config = config;
    }

    public void reload() {
        List<Rule> merged = new ArrayList<>();

        File rulesFile = config.file("rules.yml");
        if (rulesFile.exists()) {
            try {
                merged.addAll(RuleReader.fromConfig(YamlConfiguration.loadConfiguration(rulesFile), "manual"));
            } catch (Exception e) {
                plugin.getLogger().warning("[LockDown] Could not read rules.yml: " + e.getMessage());
            }
        }

        File dir = config.file("commands");
        File[] files = dir.listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".yml"));
        if (files != null) Arrays.sort(files, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
        if (files != null) {
            for (File f : files) {
                try {
                    FileConfiguration fc = YamlConfiguration.loadConfiguration(f);
                    String pluginName = fc.getString("plugin", stripExt(f.getName()));
                    merged.addAll(RuleReader.fromConfig(fc, pluginName));
                } catch (Exception e) {
                    plugin.getLogger().warning("[LockDown] Could not read " + f + ": " + e.getMessage());
                }
            }
        }

        List<Rule> deduped = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        List<Rule> active = new ArrayList<>();

        for (Rule r : merged) {
            if (seen.add(r.getId())) {
                deduped.add(r);
            }
        }

        // Preserve source order: earlier/manual rules keep priority over later scanned rules.
        for (Rule r : deduped) {
            if (r.isEnabled()) active.add(r);
        }

        rules = new CopyOnWriteArrayList<>(deduped);
        enabledRules = List.copyOf(active); // Safe immutable copy

        plugin.getLogger().info("[LockDown] Loaded " + rules.size() + " rule(s). (" + enabledRules.size() + " active)");
    }

    private static String stripExt(String name) {
        int i = name.lastIndexOf('.');
        return i < 0 ? name : name.substring(0, i);
    }

    public List<Rule> all() {
        return rules;
    }

    public List<Rule> enabled() {
        return enabledRules;
    }

    /** Optimized to iterate only over active rules */
    public Rule match(String typed) {
        if (typed == null || typed.isBlank()) return null;
        for (Rule r : enabledRules) {
            if (PatternMatcher.matchesAny(r.getPatterns(), typed)) return r;
        }
        return null;
    }
}
