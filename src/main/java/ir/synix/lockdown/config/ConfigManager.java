package ir.synix.lockdown.config;

import ir.synix.lockdown.LockDownPlugin;
import ir.synix.lockdown.util.Colors;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads configuration resources, merges missing defaults in memory, and exposes formatted messages and settings.
 */
public final class ConfigManager {

    private final LockDownPlugin plugin;
    private final Map<String, String> messages = new ConcurrentHashMap<>();
    private final Map<String, List<String>> messageLists = new ConcurrentHashMap<>();

    private Settings settings;

    public ConfigManager(LockDownPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        copyDefault("config.yml");
        copyDefault("messages.yml");
        copyDefault("rules.yml");

        ensureDir("commands");
        ensureDir("logs");
        reload();
    }

    public void reload() {
        File configFile = file("config.yml");
        FileConfiguration cfg = YamlConfiguration.loadConfiguration(configFile);

        // Do not save merged defaults here; saving through Bukkit would discard user comments.
        if (applyDefaultsAndMerge(cfg, "config.yml")) {
            plugin.getLogger().info(
                    "[LockDown] Updated config.yml in memory with new defaults. "
                            + "(Not saved to disk to preserve comments)");
        }

        settings = buildSettings(cfg);

        File msgFile = file("messages.yml");
        FileConfiguration msg = YamlConfiguration.loadConfiguration(msgFile);
        if (applyDefaultsAndMerge(msg, "messages.yml")) {
            plugin.getLogger().info(
                    "[LockDown] Updated messages.yml in memory with new defaults. "
                            + "(Not saved to disk to preserve comments)");
        }

        messages.clear();
        messageLists.clear();

        String prefix = msg.getString("prefix", "");
        for (String key : msg.getKeys(true)) {
            if (msg.isConfigurationSection(key)) continue;

            if (msg.isList(key)) {
                List<String> list = msg.getStringList(key).stream()
                        .map(value -> Colors.colorize(value.replace("{prefix}", prefix)))
                        .toList();
                messageLists.put(key, list);
            } else {
                messages.put(key, msg.getString(key, ""));
            }
        }
    }

    public Settings settings() {
        return settings;
    }

    public String msg(String key, Object... vars) {
        String raw = messages.getOrDefault(key, "");
        String withPrefix = raw.contains("{prefix}")
                ? raw.replace("{prefix}", messages.getOrDefault("prefix", ""))
                : raw;
        return Colors.colorize(Colors.replace(withPrefix, vars));
    }

    public String msgPlain(String key, Object... vars) {
        String raw = messages.getOrDefault(key, "");
        String withPrefix = raw.contains("{prefix}")
                ? raw.replace("{prefix}", messages.getOrDefault("prefix", ""))
                : raw;

        String strippedTemplate = Colors.strip(withPrefix);
        return Colors.replace(strippedTemplate, vars);
    }

    public List<String> msgList(String key) {
        return messageLists.getOrDefault(key, Collections.emptyList());
    }

    public File file(String... parts) {
        File file = plugin.getDataFolder();
        for (String part : parts) {
            file = new File(file, part);
        }
        return file;
    }

    private Settings buildSettings(FileConfiguration c) {
        Settings s = new Settings();
        s.enabled = c.getBoolean("enabled", true);
        s.debug = c.getBoolean("debug", false);

        s.discord.webhook = c.getString("discord.webhook", "");
        s.discord.username = c.getString("discord.username", "LockDown");
        s.discord.avatarUrl = c.getString("discord.avatar_url", "");

        s.smtp.host = c.getString("smtp.host", "");
        s.smtp.port = c.getInt("smtp.port", 587);
        s.smtp.username = c.getString("smtp.username", "");
        s.smtp.password = c.getString("smtp.password", "");
        s.smtp.encryption = c.getString("smtp.encryption", "starttls");
        s.smtp.from = c.getString("smtp.from", "lockdown@example.com");
        s.smtp.fromName = c.getString("smtp.from_name", "LockDown");

        s.guardians.emails = c.getStringList("guardians.emails");

        s.confirmation.codeLength = clamp(c.getInt("confirmation.code_length", 6), 3, 12);
        s.confirmation.timeoutSeconds = clamp(c.getInt("confirmation.timeout_seconds", 60), 5, 3600);
        s.confirmation.maxAttempts = clamp(c.getInt("confirmation.max_attempts", 3), 1, 20);
        s.confirmation.alphabet = c.getString("confirmation.alphabet", "digits");

        s.lockdown.unlockers = c.getStringList("lockdown.unlockers");
        s.lockdown.mode = c.getString("lockdown.mode", "all");
        s.lockdown.commands = c.getStringList("lockdown.commands");
        s.lockdown.consoleBypass = c.getBoolean("lockdown.console_bypass", true);
        s.lockdown.bypassPermission = c.getBoolean("lockdown.bypass_permission", false);
        s.lockdown.requireCode = c.getBoolean("lockdown.require_code", true);

        s.scan.safeMerge = c.getBoolean("scan.safe_merge", true);
        s.scan.defaultPluginFile = c.getString("scan.default_plugin_file", "Minecraft");

        s.audit.file = c.getString("audit.file", "logs/lockdown.log");
        s.audit.bufferSize = clamp(c.getInt("audit.buffer_size", 256), 16, 4096);

        s.mongodb.enabled = c.getBoolean("mongodb.enabled", false);
        s.mongodb.uri = c.getString("mongodb.uri", "mongodb://localhost:27017");
        s.mongodb.database = c.getString("mongodb.database", "lockdown");
        s.mongodb.collection = c.getString("mongodb.collection", "logs");
        return s;
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private void copyDefault(String filename) {
        File file = file(filename);
        if (file.exists()) return;

        try {
            plugin.saveResource(filename, false);
        } catch (Exception e) {
            plugin.getLogger().warning("[LockDown] Could not save default resource: " + filename);
        }
    }

    private void ensureDir(String dirName) {
        File directory = file(dirName);
        if (!directory.exists()) directory.mkdirs();
    }

    private boolean applyDefaultsAndMerge(FileConfiguration config, String resource) {
        try (InputStream in = plugin.getResource(resource)) {
            if (in == null) return false;

            FileConfiguration defaults = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(in, StandardCharsets.UTF_8));
            boolean changed = false;
            for (String key : defaults.getKeys(true)) {
                if (!defaults.isConfigurationSection(key) && !config.contains(key, true)) {
                    config.set(key, defaults.get(key));
                    changed = true;
                }
            }
            return changed;
        } catch (Exception e) {
            return false;
        }
    }
}
