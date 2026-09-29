package ir.synix.lockdown.lockdown;

import ir.synix.lockdown.LockDownPlugin;
import ir.synix.lockdown.admin.LockDownCommand;
import ir.synix.lockdown.approval.PendingConfirmation;
import ir.synix.lockdown.audit.AuditEntry;
import ir.synix.lockdown.command.CommandInterceptor;
import ir.synix.lockdown.config.Settings;
import ir.synix.lockdown.util.Colors;
import ir.synix.lockdown.util.SecretBox;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.metadata.FixedMetadataValue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Owns global-lockdown and frozen-player state and persists recoverable security state to disk.
 */
public final class LockdownManager {

    private final LockDownPlugin plugin;
    private final Set<UUID> frozen = ConcurrentHashMap.newKeySet();
    private volatile boolean globalLockdown = false;
    private volatile UUID lockdownEngager;
    private final AtomicLong saveVersion = new AtomicLong();

    public LockdownManager(LockDownPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean isGlobalLockdown() {
        return globalLockdown;
    }

    public UUID lockdownEngager() {
        return lockdownEngager;
    }

    public void engageGlobalLockdown(UUID engager) {
        this.globalLockdown = true;
        this.lockdownEngager = engager;
        saveState();
    }

    public void releaseGlobalLockdown() {
        this.globalLockdown = false;
        this.lockdownEngager = null;
        saveState();
    }

    public boolean isUnlocker(UUID id, String name, Settings s) {
        if (id == null) return false;
        if (s.lockdown.unlockers.contains(name)) return true;
        return s.lockdown.unlockers.contains(id.toString());
    }

    /**
     * Global command lockdown is intentionally independent from normal rules.
     *
     * Modes:
     * - all:       block every command
     * - blocklist: block only commands matching lockdown.commands
     * - allowlist: block every command except those matching lockdown.commands
     */
    public boolean isBlockedByGlobalLockdown(String command, Settings s, boolean consoleSender) {
        if (!globalLockdown) return false;
        if (consoleSender && s.lockdown.consoleBypass) return false;

        String normalized = CommandInterceptor.normalize(command);
        return GlobalCommandPolicy.isBlocked(s.lockdown.mode, s.lockdown.commands, normalized);
    }

    public void freeze(UUID id) {
        if (id != null && !id.equals(LockDownCommand.CONSOLE_UUID)) {
            frozen.add(id);
            saveState();
        }
    }

    public void unfreeze(UUID id) {
        if (id != null) {
            frozen.remove(id);
            saveState();
        }
    }

    public boolean isFrozen(UUID id) {
        return id != null && frozen.contains(id);
    }

    public Set<UUID> frozenPlayers() {
        return Set.copyOf(frozen);
    }

    public int frozenCount() {
        return frozen.size();
    }

    // ---- State Persistence --------------------

    public void saveState() {
        writeState(true);
    }

    public void saveStateNow() {
        writeState(false);
    }

    private synchronized void writeState(boolean async) {
        try {
            File file = new File(plugin.getDataFolder(), "state.yml");
            YamlConfiguration yaml = new YamlConfiguration();

            yaml.set("global-lockdown", this.globalLockdown);
            yaml.set("lockdown-engager", this.lockdownEngager != null ? this.lockdownEngager.toString() : null);

            List<String> frozenList = new ArrayList<>();
            for (UUID id : frozen) {
                frozenList.add(id.toString());
            }
            yaml.set("frozen", frozenList);

            List<Map<String, Object>> confirmList = new ArrayList<>();
            var approval = plugin.getApproval();
            if (approval != null) {
                for (PendingConfirmation pc : approval.getAllPending()) {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("senderId", pc.senderId().toString());
                    map.put("kind", pc.kind().name());
                    map.put("code", SecretBox.encrypt(plugin, pc.code()));
                    if (pc.totpSecret() != null) map.put("totpSecret", SecretBox.encrypt(plugin, pc.totpSecret()));
                    map.put("command", pc.command());
                    map.put("ruleId", pc.ruleId());
                    map.put("expiresAt", pc.expiresAt().toEpochMilli());
                    map.put("maxAttempts", pc.maxAttempts());
                    map.put("attempts", pc.attempts());
                    confirmList.add(map);
                }
            }
            yaml.set("confirmations", confirmList);

            String dump = yaml.saveToString();
            long version = saveVersion.incrementAndGet();
            Runnable task = () -> {
                if (async && version != saveVersion.get()) return;
                try {
                    Files.createDirectories(file.toPath().getParent());
                    Files.writeString(file.toPath(), dump,
                            StandardOpenOption.CREATE,
                            StandardOpenOption.TRUNCATE_EXISTING);
                } catch (Exception ex) {
                    plugin.getLogger().warning("[LockDown] State save failed: " + ex.getMessage());
                }
            };

            if (async && plugin.isEnabled()) {
                Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
            } else {
                task.run();
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[LockDown] Could not prepare state.yml: " + e.getMessage());
        }
    }

    public synchronized void loadState() {
        File file = new File(plugin.getDataFolder(), "state.yml");
        if (!file.exists()) return;

        try {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
            this.globalLockdown = yaml.getBoolean("global-lockdown", false);
            String engagerStr = yaml.getString("lockdown-engager");
            try {
                this.lockdownEngager = engagerStr != null ? UUID.fromString(engagerStr) : null;
            } catch (Exception ex) {
                this.lockdownEngager = null;
                plugin.getLogger().warning("[LockDown] Ignored invalid lockdown engager UUID in state.yml");
            }

            this.frozen.clear();
            List<String> frozenList = yaml.getStringList("frozen");
            for (String frozenId : frozenList) {
                try {
                    this.frozen.add(UUID.fromString(frozenId));
                } catch (Exception ex) {
                    plugin.getLogger().warning("[LockDown] Ignored invalid frozen UUID in state.yml: " + frozenId);
                }
            }

            if (yaml.isList("confirmations")) {
                var approval = plugin.getApproval();
                if (approval != null) {
                    for (Map<?, ?> map : yaml.getMapList("confirmations")) {
                        try {
                            UUID senderId = UUID.fromString((String) map.get("senderId"));
                            PendingConfirmation.Kind kind = PendingConfirmation.Kind.valueOf((String) map.get("kind"));
                            String code = SecretBox.decrypt(plugin, (String) map.get("code"));
                            String totpSecret = map.containsKey("totpSecret")
                                    ? SecretBox.decrypt(plugin, (String) map.get("totpSecret"))
                                    : null;
                            String command = (String) map.get("command");
                            String ruleId = (String) map.get("ruleId");
                            Instant expiresAt = Instant.ofEpochMilli(((Number) map.get("expiresAt")).longValue());
                            int maxAttempts = ((Number) map.get("maxAttempts")).intValue();
                            int attempts = ((Number) map.get("attempts")).intValue();

                            Consumer<PendingConfirmation> onSuccess;
                            Consumer<PendingConfirmation> onFailure = pc2 -> onConfirmFailureOnReload(senderId);

                            if (kind == PendingConfirmation.Kind.LOCKDOWN_ENGAGE) {
                                onSuccess = pc2 -> {
                                    engageGlobalLockdown(senderId);
                                    broadcastAdminMessage("Global command lockdown engaged.");
                                };
                            } else if (kind == PendingConfirmation.Kind.LOCKDOWN_RELEASE) {
                                onSuccess = pc2 -> {
                                    releaseGlobalLockdown();
                                    broadcastAdminMessage("Global command lockdown released.");
                                };
                            } else if (kind == PendingConfirmation.Kind.UNFREEZE) {
                                onSuccess = pc2 -> executeUnfreezeOnReload(senderId, command);
                            } else {
                                onSuccess = pc2 -> onConfirmSuccessOnReload(senderId, pc2.command());
                            }

                            if (expiresAt.isBefore(Instant.now())) {
                                onConfirmFailureOnReload(senderId);
                                continue;
                            }

                            PendingConfirmation pc = new PendingConfirmation(
                                    senderId, kind, code, totpSecret, command, ruleId, expiresAt, maxAttempts,
                                    onSuccess, onFailure
                            );
                            pc.restoreAttempts(attempts);

                            approval.registerLoaded(pc);
                        } catch (Exception ex) {
                            plugin.getLogger().warning("[LockDown] Error restoring confirmation: " + ex.getMessage());
                        }
                    }
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[LockDown] Could not load state.yml: " + e.getMessage());
        }
    }

    private void onConfirmSuccessOnReload(UUID senderId, String command) {
        unfreeze(senderId);
        Player p = Bukkit.getPlayer(senderId);
        if (p != null) {
            p.sendMessage(plugin.getConfigManager().msg("confirm_success"));
            plugin.getAudit().record(p.getName(), p.getUniqueId(), "Reload", command, null, true, AuditEntry.Result.CONFIRMED);

            String token = plugin.getApproval().issueBypass(command);
            p.setMetadata(CommandInterceptor.BYPASS_META, new FixedMetadataValue(plugin, token));
            Bukkit.getScheduler().runTask(plugin, () -> p.performCommand(command));
        } else if (senderId.equals(LockDownCommand.CONSOLE_UUID)) {
            plugin.addConsoleBypass(command);
            Bukkit.getScheduler().runTask(plugin, () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command));
        }
    }

    private void executeUnfreezeOnReload(UUID senderId, String commandPayload) {
        String targetName = commandPayload.replace("unfreeze ", "").trim();
        Player sender = Bukkit.getPlayer(senderId);

        try {
            UUID targetId = UUID.fromString(targetName);
            unfreeze(targetId);
            if (sender != null) sender.sendMessage(plugin.getConfigManager().msg("unfrozen_admin", "player", targetName));
            Player target = Bukkit.getPlayer(targetId);
            if (target != null) target.sendMessage(plugin.getConfigManager().msg("unfrozen_player"));
            return;
        } catch (IllegalArgumentException ignored) {}

        Player target = Bukkit.getPlayer(targetName);
        if (target != null) {
            unfreeze(target.getUniqueId());
            if (sender != null) sender.sendMessage(plugin.getConfigManager().msg("unfrozen_admin", "player", targetName));
            target.sendMessage(plugin.getConfigManager().msg("unfrozen_player"));
        } else {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                @SuppressWarnings("deprecation")
                OfflinePlayer op = Bukkit.getOfflinePlayer(targetName);
                if (op.hasPlayedBefore() || op.isOnline()) {
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        unfreeze(op.getUniqueId());
                        if (sender != null) sender.sendMessage(plugin.getConfigManager().msg("unfrozen_admin", "player", targetName));
                    });
                }
            });
        }
    }

    private void broadcastAdminMessage(String msg) {
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.hasPermission("lockdown.admin")) online.sendMessage(Colors.colorize("&c" + msg));
        }
    }

    private void onConfirmFailureOnReload(UUID senderId) {
        unfreeze(senderId);
        Player p = Bukkit.getPlayer(senderId);
        if (p != null) {
            p.sendMessage(plugin.getConfigManager().msg("confirm_expired"));
        }
    }
}
