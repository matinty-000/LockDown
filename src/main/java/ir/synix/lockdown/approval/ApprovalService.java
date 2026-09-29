package ir.synix.lockdown.approval;

import ir.synix.lockdown.LockDownPlugin;
import ir.synix.lockdown.util.Colors;
import ir.synix.lockdown.util.Time;

import org.bukkit.Bukkit;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages pending confirmations, confirmation codes, command-release bypass tokens, and confirmation boss bars.
 */
public final class ApprovalService {

    private static final String DIGITS = "0123456789";
    private static final String ALNUM = "0123456789abcdefghijklmnopqrstuvwxyz";

    private final LockDownPlugin plugin;
    private final SecureRandom rng = new SecureRandom();

    private final Map<UUID, PendingConfirmation> pending = new ConcurrentHashMap<>();
    private final Map<String, String> bypass = new ConcurrentHashMap<>();

    private final Map<UUID, BossBar> bossBars = new ConcurrentHashMap<>();
    private final Map<UUID, BossBar> expiringBars = new ConcurrentHashMap<>();

    public ApprovalService(LockDownPlugin plugin) {
        this.plugin = plugin;
    }

    public synchronized String generateCode() {
        int len = Math.max(3, plugin.getConfigManager().settings().confirmation.codeLength);
        String alphabet = "digits".equalsIgnoreCase(plugin.getConfigManager().settings().confirmation.alphabet) ? DIGITS : ALNUM;
        String generated = randomCode(len, alphabet);
        for (int tries = 0; tries < 64 && codeInUse(generated); tries++) {
            generated = randomCode(len, alphabet);
        }
        return generated;
    }

    private String randomCode(int len, String alphabet) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(alphabet.charAt(rng.nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    private boolean codeInUse(String code) {
        if (code == null) return false;
        for (PendingConfirmation pc : pending.values()) {
            if (pc.code() != null && pc.code().equalsIgnoreCase(code)) return true;
        }
        return false;
    }

    public Instant defaultExpiry() {
        return Instant.now().plus(Duration.ofSeconds(plugin.getConfigManager().settings().confirmation.timeoutSeconds));
    }

    public int maxAttempts() {
        return plugin.getConfigManager().settings().confirmation.maxAttempts;
    }

    public PendingConfirmation get(UUID senderId) {
        return senderId == null ? null : pending.get(senderId);
    }

    public Collection<PendingConfirmation> getAllPending() {
        return pending.values();
    }

    /** Deliberately not used for /confirm; confirmations are bound to their original sender. */
    public UUID findByCode(String code) {
        return null;
    }

    public PendingConfirmation register(PendingConfirmation c) {
        if (c.senderId() != null) {
            pending.put(c.senderId(), c);

            Player p = Bukkit.getPlayer(c.senderId());
            if (p != null) {
                registerPlayerBossBar(p);
            }

            if (plugin != null && plugin.getLockdown() != null) {
                plugin.getLockdown().saveState();
            }
        }
        return c;
    }

    public void registerLoaded(PendingConfirmation c) {
        if (c.senderId() != null) {
            pending.put(c.senderId(), c);
            Player p = Bukkit.getPlayer(c.senderId());
            if (p != null) {
                registerPlayerBossBar(p);
            }
        }
    }

    public boolean hasPending(UUID senderId) {
        return senderId != null && pending.containsKey(senderId);
    }

    public PendingConfirmation clear(UUID senderId) {
        if (senderId == null) return null;
        PendingConfirmation c = pending.remove(senderId);
        removeBossBar(senderId);
        if (plugin != null && plugin.getLockdown() != null) {
            plugin.getLockdown().saveState();
        }
        return c;
    }

    public boolean resend(UUID senderId) {
        PendingConfirmation pc = pending.get(senderId);
        if (pc == null) return false;

        Instant newExpiry = defaultExpiry();
        String playerName = Bukkit.getPlayer(senderId) != null ? Bukkit.getPlayer(senderId).getName() : "Unknown";

        if (pc.totpSecret() != null) {
            pc.reset(null, pc.totpSecret(), newExpiry);
            String body = plugin.getConfigManager().msgPlain("confirm_notify_totp_plain",
                    "player", playerName,
                    "command", pc.command(),
                    "plugin", pluginNameFor(pc),
                    "time", Time.remaining(newExpiry));
            plugin.getNotifier().send("LockDown — confirmation required", body);
        } else {
            String newCode = generateCode();
            pc.reset(newCode, null, newExpiry);
            String body = plugin.getConfigManager().msgPlain("confirm_notify_plain",
                    "player", playerName,
                    "command", pc.command(),
                    "plugin", pluginNameFor(pc),
                    "code", newCode,
                    "time", Time.remaining(newExpiry));
            plugin.getNotifier().send("LockDown — confirmation required (NEW CODE)", body);
        }

        BossBar bar = bossBars.get(senderId);
        if (bar != null) {
            bar.setColor(BarColor.BLUE);
            bar.setProgress(1.0);
            bar.setTitle(Colors.colorize("&#89CFF0Awaiting Code Confirmation &7- &f" + Time.remaining(newExpiry)));
        }

        if (plugin != null && plugin.getLockdown() != null) {
            plugin.getLockdown().saveState();
        }

        return true;
    }

    public Result confirm(UUID senderId, String guess) {
        PendingConfirmation c = get(senderId);
        if (c == null) return Result.NONE;
        if (c.isExpired()) {
            pending.remove(senderId);
            removeBossBar(senderId);
            c.fireFailure();
            if (plugin != null && plugin.getLockdown() != null) plugin.getLockdown().saveState();
            return Result.EXPIRED;
        }
        if (c.submit(guess)) {
            pending.remove(senderId);
            removeBossBar(senderId);
            c.fireSuccess();
            if (plugin != null && plugin.getLockdown() != null) plugin.getLockdown().saveState();
            return Result.SUCCESS;
        }
        if (c.attemptsLeft() <= 0) {
            pending.remove(senderId);
            removeBossBar(senderId);
            c.fireFailure();
            if (plugin != null && plugin.getLockdown() != null) plugin.getLockdown().saveState();
            return Result.FAILED;
        }
        if (plugin != null && plugin.getLockdown() != null) {
            plugin.getLockdown().saveState();
        }
        return Result.WRONG;
    }

    public void purgeExpired() {
        boolean changed = false;

        Iterator<Map.Entry<UUID, PendingConfirmation>> it = pending.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, PendingConfirmation> entry = it.next();
            PendingConfirmation pc = entry.getValue();
            if (pc.isExpired() && !pc.isFailureFired()) {
                pc.fireFailure();
                pending.remove(entry.getKey(), pc);
                removeBossBar(entry.getKey());
                changed = true;
            }
        }

        Iterator<Map.Entry<UUID, BossBar>> barIt = bossBars.entrySet().iterator();
        while (barIt.hasNext()) {
            Map.Entry<UUID, BossBar> entry = barIt.next();
            UUID senderId = entry.getKey();
            BossBar bar = entry.getValue();
            PendingConfirmation pc = pending.get(senderId);
            Player p = Bukkit.getPlayer(senderId);

            if (p == null || !p.isOnline()) {
                bar.removeAll();
                barIt.remove();
                continue;
            }

            if (pc == null) {
                barIt.remove();
                if (bar.getProgress() > 0.0) {
                    bar.setProgress(0.0);
                    bar.setColor(BarColor.RED);
                    bar.setTitle(Colors.colorize("&#dc2626Code Expired &7- &fType /ld resend"));
                    expiringBars.put(senderId, bar);
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        BossBar expired = expiringBars.remove(senderId);
                        if (expired != null) expired.removeAll();
                    }, 100L);
                } else {
                    bar.removeAll();
                }
                continue;
            }

            long totalSec = plugin.getConfigManager().settings().confirmation.timeoutSeconds;
            long remainingMilli = pc.expiresAt().toEpochMilli() - Instant.now().toEpochMilli();
            double progress = (double) remainingMilli / (totalSec * 1000.0);
            progress = Math.max(0.0, Math.min(1.0, progress));

            bar.setProgress(progress);
            if (progress < 0.25) {
                bar.setColor(BarColor.RED);
            } else {
                bar.setColor(BarColor.BLUE);
            }
            bar.setTitle(Colors.colorize("&#89CFF0Awaiting Code Confirmation &7- &f" + Time.remaining(pc.expiresAt())));
        }

        if (changed && plugin != null && plugin.getLockdown() != null) {
            plugin.getLockdown().saveState();
        }
    }

    public void registerPlayerBossBar(Player p) {
        PendingConfirmation pc = pending.get(p.getUniqueId());
        if (pc == null) return;

        BossBar expired = expiringBars.remove(p.getUniqueId());
        if (expired != null) expired.removeAll();

        BossBar bar = bossBars.remove(p.getUniqueId());
        if (bar != null) bar.removeAll();

        bar = Bukkit.createBossBar(
                Colors.colorize("&#89CFF0Awaiting Code Confirmation &7- &f" + Time.remaining(pc.expiresAt())),
                BarColor.BLUE,
                BarStyle.SOLID
        );
        bar.addPlayer(p);
        bossBars.put(p.getUniqueId(), bar);
    }

    private void removeBossBar(UUID senderId) {
        BossBar bar = bossBars.remove(senderId);
        if (bar != null) {
            bar.removeAll();
        }
        BossBar expired = expiringBars.remove(senderId);
        if (expired != null) {
            expired.removeAll();
        }
    }

    public String issueBypass(String command) {
        String token = Long.toHexString(rng.nextLong()) + Long.toHexString(System.nanoTime());
        bypass.put(token, command == null ? "" : command.toLowerCase(Locale.ROOT));
        return token;
    }

    public boolean consumeBypass(String token, String command) {
        if (token == null) return false;
        String bound = bypass.remove(token);
        return bound != null && bound.equalsIgnoreCase(command == null ? "" : command);
    }

    private String pluginNameFor(PendingConfirmation pc) {
        if (pc == null) return "Minecraft";
        if (pc.kind() != PendingConfirmation.Kind.COMMAND) return "LockDown";
        if (plugin.getRules() != null) {
            for (var rule : plugin.getRules().all()) {
                if (rule.getId().equalsIgnoreCase(pc.ruleId())) return rule.getPlugin();
            }
        }
        return "Minecraft";
    }

    public enum Result { SUCCESS, WRONG, EXPIRED, FAILED, NONE }
}
