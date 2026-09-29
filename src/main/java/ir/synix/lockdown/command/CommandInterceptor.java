package ir.synix.lockdown.command;

import ir.synix.lockdown.LockDownPlugin;
import ir.synix.lockdown.admin.LockDownCommand;
import ir.synix.lockdown.approval.PendingConfirmation;
import ir.synix.lockdown.audit.AuditEntry;
import ir.synix.lockdown.audit.AuditLogger;
import ir.synix.lockdown.config.ConfigManager;
import ir.synix.lockdown.lockdown.LockdownManager;
import ir.synix.lockdown.notify.CompositeNotifier;
import ir.synix.lockdown.rules.RuleEngine;
import ir.synix.lockdown.rules.model.ActionPlan;
import ir.synix.lockdown.rules.model.Rule;
import ir.synix.lockdown.util.Colors;
import ir.synix.lockdown.util.CommandMapAccess;
import ir.synix.lockdown.util.Time;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.metadata.FixedMetadataValue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Intercepts player and server commands, applies global lockdown policy, and executes matching security rules.
 */
public final class CommandInterceptor implements Listener {

    public static final String BYPASS_META = "lockdown:bypass";

    private final LockDownPlugin plugin;
    private final ConfigManager config;
    private final RuleEngine rules;
    private final LockdownManager lockdown;
    private final AuditLogger audit;
    private final CompositeNotifier notifier;

    public CommandInterceptor(LockDownPlugin plugin, ConfigManager config, RuleEngine rules,
                              LockdownManager lockdown, AuditLogger audit, CompositeNotifier notifier) {
        this.plugin = plugin;
        this.config = config;
        this.rules = rules;
        this.lockdown = lockdown;
        this.audit = audit;
        this.notifier = notifier;
    }

    /**
     * Normalizes the command string securely to prevent typical bypasses:
     * - Strips the leading slash
     * - Parses out all nested /execute runs
     * - Drops all plugin prefixes (e.g. minecraft:op -> op)
     * - Normalizes multiple spaces into a single space
     */
    public static String normalize(String raw) {
        if (raw == null) return "";
        String s = raw.startsWith("/") ? raw.substring(1) : raw;

        boolean changed;
        do {
            changed = false;
            String before = s;

            // Drop namespace prefixes continuously
            s = s.replaceAll("^[a-zA-Z0-9_-]+:", "").trim();

            // Extract out of execute syntax iteratively to prevent nested evasion
            if (s.toLowerCase(Locale.ROOT).startsWith("execute ")) {
                int runIdx = s.toLowerCase(Locale.ROOT).indexOf(" run ");
                if (runIdx != -1) {
                    s = s.substring(runIdx + 5).trim();
                }
            }

            if (!s.equals(before)) changed = true;
        } while (changed);

        s = s.replaceAll("\\s+", " ");
        return s.trim();
    }

    public static boolean isOwnConfirmCommand(String typed, LockDownPlugin plugin) {
        String low = normalize(typed).toLowerCase(Locale.ROOT);
        List<String> labels = new ArrayList<>();
        labels.add("lockdown");
        PluginCommand pc = plugin.getCommand("lockdown");
        if (pc != null) labels.addAll(pc.getAliases());

        for (String lbl : labels) {
            String pfx = lbl.toLowerCase(Locale.ROOT);
            if (low.equals(pfx + " confirm") || low.startsWith(pfx + " confirm ") ||
                    low.equals(pfx + " resend") || low.startsWith(pfx + " resend ") ||
                    low.equals(pfx + " auth") || low.startsWith(pfx + " auth ")) {
                return true;
            }
        }
        return false;
    }

    public static boolean isOwnLockDownCommand(String typed, LockDownPlugin plugin) {
        String low = normalize(typed).toLowerCase(Locale.ROOT);
        List<String> labels = new ArrayList<>();
        labels.add("lockdown");
        PluginCommand pc = plugin.getCommand("lockdown");
        if (pc != null) labels.addAll(pc.getAliases());
        for (String lbl : labels) {
            String pfx = lbl.toLowerCase(Locale.ROOT);
            if (low.equals(pfx) || low.startsWith(pfx + " ")) return true;
        }
        return false;
    }

    private String canonicalizeOwnCommand(String typed) {
        String low = normalize(typed).toLowerCase(Locale.ROOT);
        List<String> labels = new ArrayList<>();
        labels.add("lockdown");
        PluginCommand pc = plugin.getCommand("lockdown");
        if (pc != null) labels.addAll(pc.getAliases());
        for (String lbl : labels) {
            String pfx = lbl.toLowerCase(Locale.ROOT);
            if (low.equals(pfx) || low.startsWith(pfx + " ")) {
                return "lockdown" + low.substring(pfx.length());
            }
        }
        return typed;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlayer(PlayerCommandPreprocessEvent e) {
        if (!config.settings().enabled) return;

        Player p = e.getPlayer();
        String raw = e.getMessage();
        String typed = canonicalizeOwnCommand(normalize(raw));

        boolean ruleBypass = p.hasPermission("lockdown.bypass");
        boolean globalBypass = ruleBypass && config.settings().lockdown.bypassPermission;
        if (lockdown.isGlobalLockdown() && !globalBypass) {
            if (lockdown.isBlockedByGlobalLockdown(typed, config.settings(), false)) {
                blockForGlobalLockdown(p, typed);
                e.setCancelled(true);
                return;
            }
        }

        if (isOwnLockDownCommand(raw, plugin)) return;
        if (ruleBypass) return;

        String rawNoSlash = raw.startsWith("/") ? raw.substring(1) : raw;
        if (p.hasMetadata(BYPASS_META) && consumeBypassMeta(p, rawNoSlash)) {
            return;
        }

        Decision d = decide(p, typed, rawNoSlash);
        if (d.isCancelled()) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onServer(ServerCommandEvent e) {
        if (!config.settings().enabled) return;
        CommandSender s = e.getSender();
        String raw = e.getCommand();
        String typed = canonicalizeOwnCommand(normalize(raw));
        boolean consoleSender = s instanceof ConsoleCommandSender;
        boolean ruleBypass = s.hasPermission("lockdown.bypass");
        boolean globalBypass = ruleBypass && config.settings().lockdown.bypassPermission;

        if (lockdown.isGlobalLockdown() && !globalBypass) {
            if (lockdown.isBlockedByGlobalLockdown(typed, config.settings(), consoleSender)) {
                blockForGlobalLockdown(s, typed);
                e.setCancelled(true);
                return;
            }
        }

        if (isOwnLockDownCommand(raw, plugin)) return;
        if (ruleBypass) return;
        if (plugin.consumeConsoleBypass(raw)) return;

        Decision d = decide(s, typed, raw);
        if (d.isCancelled()) e.setCancelled(true);
    }

    private Decision decide(CommandSender sender, String typed, String rawNoSlash) {
        if (typed.isBlank()) return Decision.allow();

        UUID id = senderId(sender);
        String name = senderName(sender);

        Rule match = rules.match(typed);
        if (match == null) return Decision.allow();

        boolean authorized = authorized(sender, typed, match);
        ActionPlan plan = match.formFor(authorized);
        if (!plan.hasAnyAction()) return Decision.allow();

        AuditEntry.Result plannedResult = plan.isRequireConfirmation()
                ? AuditEntry.Result.AWAITING_CONFIRM
                : (plan.isFreeze() ? AuditEntry.Result.FROZEN : (plan.isBlock() ? AuditEntry.Result.BLOCKED : AuditEntry.Result.ALLOWED));
        if (plan.isLog()) {
            audit.record(name, id, match.getPlugin(), typed, match.getId(), authorized, plannedResult);
        }

        if (plan.isNotify() && !plan.isRequireConfirmation()) {
            sendAlert(name, match, typed, authorized);
        }

        if (plan.isBlock() && plan.isRequireConfirmation()) {
            sender.sendMessage(config.msg("no_permission"));
            return Decision.block();
        }

        if (plan.isRequireConfirmation()) {
            // Forward rawNoSlash so proper context is maintained when releasing
            beginConfirmation(sender, id, typed, match, authorized, rawNoSlash);
            return Decision.block();
        }

        if (plan.isFreeze() && id != null) {
            lockdown.freeze(id);
            if (sender instanceof Player p) {
                p.sendTitle(config.msg("freeze_title"), config.msg("freeze_subtitle"), 10, 70, 10);
                p.sendMessage(config.msg("freeze_applied_failure"));
            }
        }

        if (plan.isBlock() || plan.isFreeze()) {
            if (!plan.isFreeze()) {
                sender.sendMessage(config.msg("no_permission"));
            }
            return Decision.block();
        }

        return Decision.allow();
    }

    private void blockForGlobalLockdown(CommandSender sender, String typed) {
        UUID id = senderId(sender);
        sender.sendMessage(config.msg("lockdown_block"));
        audit.record(senderName(sender), id, pluginOf(typed), typed, null,
                authorized(sender, typed, null), AuditEntry.Result.LOCKDOWN_BLOCK);
    }

    private void beginConfirmation(CommandSender sender, UUID id, String typed, Rule rule, boolean authorized, String rawNoSlash) {
        boolean hasTotp = plugin.getAuthenticatorManager().hasAuth(id);
        if (!hasTotp && !plugin.getNotifier().enabled()) {
            sender.sendMessage(config.msg("prefix") + Colors.colorize(
                    "&cNo Discord, email, or personal Authenticator is configured. "
                            + "The command was blocked without freezing you."));
            return;
        }
        String code = hasTotp ? null : plugin.getApproval().generateCode();
        String totpSecret = hasTotp ? plugin.getAuthenticatorManager().getSecret(id) : null;
        Instant expiry = plugin.getApproval().defaultExpiry();

        PendingConfirmation pending = new PendingConfirmation(
                id, PendingConfirmation.Kind.COMMAND, code, totpSecret, rawNoSlash, rule.getId(), expiry,
                plugin.getApproval().maxAttempts(),
                pc -> onConfirmSuccess(sender, pc.command()),
                pc -> onConfirmFailure(sender, rule, id));

        if (plugin.getApproval().hasPending(id)) {
            plugin.getApproval().clear(id);
            lockdown.unfreeze(id);
        }
        plugin.getApproval().register(pending);
        lockdown.freeze(id);
        if (sender instanceof Player p) plugin.getFreezeListener().showLogin(p);

        if (hasTotp) {
            String body = config.msgPlain("confirm_notify_totp_plain",
                    "player", senderName(sender),
                    "command", typed,
                    "plugin", rule.getPlugin(),
                    "time", Time.remaining(expiry));
            plugin.getNotifier().send("LockDown — TOTP required", body);
            sender.sendMessage(config.msg("confirm_prompt_totp", "time", Time.remaining(expiry)));
        } else {
            String body = config.msgPlain("confirm_notify_plain",
                    "player", senderName(sender),
                    "command", typed,
                    "plugin", rule.getPlugin(),
                    "code", code,
                    "time", Time.remaining(expiry));
            plugin.getNotifier().send("LockDown — confirmation required", body);
            sender.sendMessage(config.msg("confirm_prompt", "time", Time.remaining(expiry)));
        }
    }

    private void onConfirmSuccess(CommandSender sender, String command) {
        UUID id = senderId(sender);
        lockdown.unfreeze(id);
        sender.sendMessage(config.msg("confirm_success"));
        audit.record(senderName(sender), id, pluginOf(command), command, null,
                true, AuditEntry.Result.CONFIRMED);

        if (sender instanceof Player p) {
            String token = plugin.getApproval().issueBypass(command);
            p.setMetadata(BYPASS_META, new FixedMetadataValue(plugin, token));
            Bukkit.getScheduler().runTask(plugin, () -> p.performCommand(command));
        } else {
            plugin.addConsoleBypass(command);
            Bukkit.getScheduler().runTask(plugin, () -> Bukkit.dispatchCommand(sender, command));
        }
    }

    private void onConfirmFailure(CommandSender sender, Rule rule, UUID id) {
        if (id != null) lockdown.unfreeze(id);

        ActionPlan fail = rule.getOnFailure();
        if (fail != null && fail.isFreeze() && id != null) {
            lockdown.freeze(id);
            if (sender instanceof Player p) {
                p.sendMessage(config.msg("freeze_applied_failure"));
                plugin.getFreezeListener().showLogin(p);
            }
        }
        if (fail != null && fail.isNotify()) {
            plugin.getNotifier().send("LockDown — confirmation failed",
                    config.msgPlain("alert_plain",
                            "player", senderName(sender),
                            "command", rule.getId(),
                            "plugin", rule.getPlugin(),
                            "rule", rule.getId(),
                            "authorized", "false"));
        }
        if (fail != null && fail.isLog()) {
            audit.record(senderName(sender), id, rule.getPlugin(), rule.getId(), rule.getId(),
                    false, AuditEntry.Result.FAILED);
        }
        sender.sendMessage(config.msg("confirm_expired"));
    }

    private boolean authorized(CommandSender sender, String typed, Rule rule) {
        if (rule != null && rule.getPermissionHint() != null && !rule.getPermissionHint().isBlank()) {
            return sender.hasPermission(rule.getPermissionHint());
        }
        Command cmd = CommandMapAccess.lookup(firstToken(typed), plugin.getLogger());
        return PermissionResolver.isAuthorized(sender, cmd);
    }

    private void sendAlert(String name, Rule rule, String typed, boolean authorized) {
        String body = config.msgPlain("alert_plain",
                "player", name,
                "command", typed,
                "plugin", rule.getPlugin(),
                "rule", rule.getId(),
                "authorized", String.valueOf(authorized));
        plugin.getNotifier().send("LockDown alert", body);
    }

    private static String firstToken(String s) {
        s = s.trim();
        int i = s.indexOf(' ');
        return i < 0 ? s : s.substring(0, i);
    }

    private static String senderName(CommandSender s) {
        return s instanceof Player p ? p.getName() : "CONSOLE";
    }

    private static UUID senderId(CommandSender s) {
        return s instanceof Player p ? p.getUniqueId() : LockDownCommand.CONSOLE_UUID;
    }

    private String pluginOf(String typed) {
        Command cmd = CommandMapAccess.lookup(firstToken(typed), plugin.getLogger());
        return cmd == null ? "Unknown" : CommandMapAccess.ownerOf(cmd, plugin.getLogger());
    }

    private boolean consumeBypassMeta(Player p, String typed) {
        var meta = p.getMetadata(BYPASS_META);
        if (meta.isEmpty()) return false;
        String token = meta.get(0).asString();
        if (plugin.getApproval().consumeBypass(token, typed)) {
            p.removeMetadata(BYPASS_META, plugin);
            return true;
        }
        return false;
    }

    private record Decision(boolean isCancelled) {
        static Decision allow() { return new Decision(false); }
        static Decision block() { return new Decision(true); }
    }
}
