package ir.synix.lockdown.approval;

import ir.synix.lockdown.LockDownPlugin;
import ir.synix.lockdown.util.SecretBox;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads, stores, and updates encrypted per-player TOTP authenticator secrets.
 */
public final class AuthenticatorManager {
    private final Map<UUID, String> secrets = new ConcurrentHashMap<>();
    private final LockDownPlugin plugin;
    private final File file;

    public AuthenticatorManager(LockDownPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "auth.yml");
        load();
    }

    public void load() {
        if (!file.exists()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        boolean migrated = false;
        for (String key : yaml.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(key);
                String secret = SecretBox.decrypt(plugin, yaml.getString(key));
                if (secret != null && !secret.isBlank()) {
                    secrets.put(uuid, secret);
                    if (!String.valueOf(yaml.getString(key)).startsWith("enc:v1:")) migrated = true;
                }
            } catch (Exception ex) {
                plugin.getLogger().warning("[LockDown] Ignored invalid auth.yml entry '" + key + "': " + ex.getMessage());
            }
        }
        if (migrated) save();
    }

    public void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<UUID, String> entry : secrets.entrySet()) {
            yaml.set(entry.getKey().toString(), SecretBox.encrypt(plugin, entry.getValue()));
        }
        try {
            yaml.save(file);
        } catch (Exception e) {
            plugin.getLogger().warning("[LockDown] Could not save auth.yml: " + e.getMessage());
        }
    }

    public String getSecret(UUID uuid) {
        return uuid == null ? null : secrets.get(uuid);
    }

    public void setSecret(UUID uuid, String secret) {
        if (uuid == null) return;
        if (secret == null) secrets.remove(uuid);
        else secrets.put(uuid, secret);
        save();
    }

    public boolean hasAuth(UUID uuid) {
        return uuid != null && secrets.containsKey(uuid);
    }
}
