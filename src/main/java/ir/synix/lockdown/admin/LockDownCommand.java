package ir.synix.lockdown.admin;

import ir.synix.lockdown.LockDownPlugin;
import ir.synix.lockdown.approval.PendingConfirmation;
import ir.synix.lockdown.audit.AuditEntry;
import ir.synix.lockdown.command.CommandIndex;
import ir.synix.lockdown.lockdown.LockdownManager;
import ir.synix.lockdown.util.Colors;
import ir.synix.lockdown.util.CommandMapAccess;
import ir.synix.lockdown.util.Time;
import ir.synix.lockdown.util.TotpUtil;

import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Text;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles the /lockdown administrative command, confirmation commands, authenticator setup, and tab completion.
 */
public final class LockDownCommand implements CommandExecutor, TabCompleter, Listener {

    private static final String PERM_ADMIN = "lockdown.admin";
    public static final UUID CONSOLE_UUID = new UUID(0, 0);

    private final LockDownPlugin plugin;
    private final Map<UUID, String> pendingAuthSetups = new ConcurrentHashMap<>();
    private final Map<UUID, Long> resendCooldowns = new ConcurrentHashMap<>();

    public LockDownCommand(LockDownPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        pendingAuthSetups.remove(e.getPlayer().getUniqueId());
        resendCooldowns.remove(e.getPlayer().getUniqueId());
    }

    private void sendMsg(CommandSender s, String rawText) {
        s.sendMessage(Colors.colorize(rawText));
    }

    private void sendPrefixedMsg(CommandSender s, String rawText) {
        s.sendMessage(plugin.getConfigManager().msg("prefix") + Colors.colorize(rawText));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            help(sender, label);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("confirm")) return doConfirm(sender, args);
        if (sub.equals("resend")) return doResend(sender);
        if (sub.equals("auth")) return doAuth(sender, args);

        if (!sender.hasPermission(PERM_ADMIN)) {
            sender.sendMessage(plugin.getConfigManager().msg("no_permission"));
            return true;
        }

        if (sub.equals("status")) { status(sender); return true; }

        switch (sub) {
            case "reload" -> reload(sender);
            case "scan"   -> scan(sender, args);
            case "gui"    -> gui(sender);
            case "rules"  -> rules(sender);
            case "lock"   -> lock(sender);
            case "unlock" -> unlock(sender);
            case "unfreeze" -> unfreeze(sender, args);
            case "help"   -> help(sender, label);
            default -> sender.sendMessage(plugin.getConfigManager().msg("unknown_subcommand"));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1) {
            List<String> subs = new ArrayList<>(List.of("confirm", "resend", "auth"));
            if (sender.hasPermission(PERM_ADMIN)) {
                subs.addAll(List.of("reload", "scan", "gui", "rules", "lock", "unlock", "unfreeze", "help"));
            }
            return filter(subs, args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("scan") && sender.hasPermission(PERM_ADMIN)) {
            return filter(List.of("--force"), args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("auth")) {
            return filter(List.of("setup", "verify", "disable"), args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("unfreeze") && sender.hasPermission(PERM_ADMIN)) {
            List<String> frozenNames = new ArrayList<>();
            for (UUID id : plugin.getLockdown().frozenPlayers()) {
                Player p = Bukkit.getPlayer(id);
                if (p != null) {
                    frozenNames.add(p.getName());
                } else {
                    frozenNames.add(id.toString());
                }
            }
            return filter(frozenNames, args[1]);
        }
        return List.of();
    }

    private List<String> filter(List<String> options, String typed) {
        String low = typed.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String s : options) {
            if (s.toLowerCase(Locale.ROOT).startsWith(low)) out.add(s);
        }
        return out;
    }

    private void reload(CommandSender s) {
        plugin.reloadAll();
        s.sendMessage(plugin.getConfigManager().msg("reloaded"));
    }

    private void scan(CommandSender s, String[] args) {
        boolean force = args.length > 1 && args[1].equalsIgnoreCase("--force");
        s.sendMessage(plugin.getConfigManager().msg("scan_started"));

        Map<String, Command> known = CommandMapAccess.knownCommands(plugin.getLogger());

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            CommandIndex.ScanResult r = plugin.getCommandIndex().scan(force, known);
            s.sendMessage(plugin.getConfigManager().msg("scan_done",
                    "count", r.commandCount(), "files", r.fileCount()));
            if (r.forced()) s.sendMessage(plugin.getConfigManager().msg("scan_force_notice"));
            Bukkit.getScheduler().runTask(plugin, plugin::reloadRules);
        });
    }

    private void gui(CommandSender s) {
        if (!(s instanceof Player p)) {
            s.sendMessage(plugin.getConfigManager().msg("player_only"));
            return;
        }
        plugin.getGui().openMain(p);
    }

    private void rules(CommandSender s) {
        var all = plugin.getRules().all();
        if (all.isEmpty()) {
            sendPrefixedMsg(s, "&7No rules loaded.");
            return;
        }
        sendPrefixedMsg(s, "&7Loaded rules (" + all.size() + "):");
        for (var r : all) {
            sendMsg(s, " &8- " + (r.isEnabled() ? "&a" : "&7") + r.getId()
                    + " &8(" + r.getPlugin() + ") &7" + String.join(", ", r.getPatterns()));
        }
    }

    private boolean doAuth(CommandSender s, String[] args) {
        if (!(s instanceof Player p)) {
            s.sendMessage(plugin.getConfigManager().msg("player_only"));
            return true;
        }
        if (args.length < 2) {
            sendPrefixedMsg(s, "&7Usage: /lockdown auth <setup|verify|disable> [code]");
            return true;
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        UUID id = p.getUniqueId();

        if (action.equals("setup")) {
            if (plugin.getAuthenticatorManager().hasAuth(id)) {
                sendPrefixedMsg(s, "&cYou already have an Authenticator linked.");
                return true;
            }
            String secret = TotpUtil.generateSecret();
            pendingAuthSetups.put(id, secret);

            sendPrefixedMsg(s, "&7--- &bAuthenticator Setup &7---");
            sendPrefixedMsg(s, "&7Your secret key is shown below. &e&nClick it to copy&r&7.");

            TextComponent secretComponent = new TextComponent(secret);
            secretComponent.setColor(ChatColor.AQUA);
            secretComponent.setBold(true);
            secretComponent.setUnderlined(true);
            secretComponent.setClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, secret));
            secretComponent.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                    new Text("§bClick to copy secret key")
            ));

            TextComponent finalMessage = new TextComponent(TextComponent.fromLegacyText(plugin.getConfigManager().msg("prefix")));
            finalMessage.addExtra("§7» Key: ");
            finalMessage.addExtra(secretComponent);
            p.spigot().sendMessage(finalMessage);

            sendPrefixedMsg(s, "&7Add this manually in Google Authenticator, then run:");
            sendPrefixedMsg(s, "&b/lockdown auth verify <code>");
            return true;
        }
        if (action.equals("verify")) {
            if (args.length < 3) {
                sendPrefixedMsg(s, "&7Usage: /lockdown auth verify <code>");
                return true;
            }
            String secret = pendingAuthSetups.get(id);
            if (secret == null) {
                sendPrefixedMsg(s, "&cYou need to run /lockdown auth setup first.");
                return true;
            }
            if (TotpUtil.verifyAuthenticatorChange(secret, args[2])) {
                plugin.getAuthenticatorManager().setSecret(id, secret);
                pendingAuthSetups.remove(id);
                s.sendMessage(plugin.getConfigManager().msg("auth_verify_success"));
            } else {
                s.sendMessage(plugin.getConfigManager().msg("auth_verify_failed"));
            }
            return true;
        }
        if (action.equals("disable")) {
            if (!plugin.getAuthenticatorManager().hasAuth(id)) {
                sendPrefixedMsg(s, "&cYou do not have an Authenticator linked.");
                return true;
            }
            if (args.length < 3) {
                sendPrefixedMsg(s, "&7Usage: /lockdown auth disable <code>");
                return true;
            }
            String secret = plugin.getAuthenticatorManager().getSecret(id);
            if (TotpUtil.verifyAuthenticatorChange(secret, args[2])) {
                plugin.getAuthenticatorManager().setSecret(id, null);
                s.sendMessage(plugin.getConfigManager().msg("auth_disabled"));
            } else {
                s.sendMessage(plugin.getConfigManager().msg("auth_verify_failed"));
            }
            return true;
        }
        sendPrefixedMsg(s, "&7Usage: /lockdown auth <setup|verify|disable> [code]");
        return true;
    }

    private void lock(CommandSender s) {
        LockdownManager lm = plugin.getLockdown();
        if (lm.isGlobalLockdown()) {
            s.sendMessage(plugin.getConfigManager().msg("lockdown_already"));
            return;
        }
        beginLockFlow(s, PendingConfirmation.Kind.LOCKDOWN_ENGAGE, "lockdown_notify_engage_plain");
    }

    private void unlock(CommandSender s) {
        LockdownManager lm = plugin.getLockdown();
        if (!lm.isGlobalLockdown()) {
            s.sendMessage(plugin.getConfigManager().msg("lockdown_not_active"));
            return;
        }
        beginLockFlow(s, PendingConfirmation.Kind.LOCKDOWN_RELEASE, "lockdown_notify_release_plain");
    }

    private void beginLockFlow(CommandSender s, PendingConfirmation.Kind kind, String msgKey) {
        UUID id = senderId(s);
        boolean requireCode = plugin.getConfigManager().settings().lockdown.requireCode;
        if (!requireCode) {
            applyLock(s, kind);
            return;
        }

        if (plugin.getApproval().hasPending(id)) {
            sendPrefixedMsg(s, "&cYou already have a pending confirmation. Please complete or wait for it to expire.");
            return;
        }

        boolean hasTotp = plugin.getAuthenticatorManager().hasAuth(id);
        if (!hasTotp && !plugin.getNotifier().enabled()) {
            sendPrefixedMsg(s,
                    "&cNo Discord, email, or personal Authenticator is configured. "
                            + "The protected action was blocked without freezing you.");
            return;
        }
        String code = hasTotp ? null : plugin.getApproval().generateCode();
        String totpSecret = hasTotp ? plugin.getAuthenticatorManager().getSecret(id) : null;
        var expiry = plugin.getApproval().defaultExpiry();

        PendingConfirmation pc = new PendingConfirmation(id, kind, code, totpSecret, "", kind.name(), expiry,
                plugin.getApproval().maxAttempts(),
                pc2 -> applyLock(s, kind),
                pc2 -> s.sendMessage(plugin.getConfigManager().msg("confirm_expired")));
        plugin.getApproval().register(pc);

        if (hasTotp) {
            String totpMsgKey = msgKey.replace("_plain", "_totp_plain");
            String body = plugin.getConfigManager().msgPlain(totpMsgKey,
                    "player", nameOf(s), "time", Time.remaining(expiry));
            plugin.getNotifier().send("LockDown — " + kind.name(), body);
            s.sendMessage(plugin.getConfigManager().msg("confirm_prompt_totp", "time", Time.remaining(expiry)));
        } else {
            String body = plugin.getConfigManager().msgPlain(msgKey,
                    "player", nameOf(s), "code", code, "time", Time.remaining(expiry));
            plugin.getNotifier().send("LockDown — " + kind.name(), body);
            s.sendMessage(plugin.getConfigManager().msg("lockdown_code_sent", "time", Time.remaining(expiry)));
        }
    }

    private void applyLock(CommandSender s, PendingConfirmation.Kind kind) {
        LockdownManager lm = plugin.getLockdown();
        String msg;
        if (kind == PendingConfirmation.Kind.LOCKDOWN_ENGAGE) {
            lm.engageGlobalLockdown(senderId(s));
            msg = plugin.getConfigManager().msg("lockdown_engaged", "player", nameOf(s));
        } else {
            lm.releaseGlobalLockdown();
            msg = plugin.getConfigManager().msg("lockdown_released", "player", nameOf(s));
        }

        for (Player online : Bukkit.getOnlinePlayers()) {
            online.sendMessage(msg);
        }
        if (!(s instanceof Player)) {
            s.sendMessage(msg);
        }
    }

    private void unfreeze(CommandSender s, String[] args) {
        if (args.length < 2) {
            sendPrefixedMsg(s, "&7Usage: /lockdown unfreeze <player>");
            return;
        }
        Player target = Bukkit.getPlayer(args[1]);
        if (target != null) {
            if (!plugin.getLockdown().isFrozen(target.getUniqueId())) {
                s.sendMessage(plugin.getConfigManager().msg("not_frozen"));
                return;
            }
            beginUnfreezeFlow(s, target.getUniqueId(), target.getName());
            return;
        }

        // Run HTTP dependent offline lookup on an async thread to prevent main thread stalling
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            UUID targetId = null;
            String targetName = args[1];
            try {
                targetId = UUID.fromString(args[1]);
            } catch (IllegalArgumentException e) {
                @SuppressWarnings("deprecation")
                OfflinePlayer op = Bukkit.getOfflinePlayer(args[1]);
                if (op.hasPlayedBefore() || op.isOnline()) {
                    targetId = op.getUniqueId();
                    targetName = op.getName();
                }
            }

            UUID finalTargetId = targetId;
            String finalTargetName = targetName;

            Bukkit.getScheduler().runTask(plugin, () -> {
                if (finalTargetId == null || !plugin.getLockdown().isFrozen(finalTargetId)) {
                    s.sendMessage(plugin.getConfigManager().msg("not_frozen"));
                    return;
                }
                beginUnfreezeFlow(s, finalTargetId, finalTargetName);
            });
        });
    }

    private void beginUnfreezeFlow(CommandSender s, UUID targetId, String targetName) {
        UUID id = senderId(s);

        if (plugin.getApproval().hasPending(id)) {
            sendPrefixedMsg(s, "&cYou already have a pending confirmation. Please complete or wait for it to expire.");
            return;
        }

        boolean requireCode = plugin.getConfigManager().settings().lockdown.requireCode;
        if (!requireCode) {
            executeUnfreeze(s, targetId, targetName);
            return;
        }

        boolean hasTotp = plugin.getAuthenticatorManager().hasAuth(id);
        if (!hasTotp && !plugin.getNotifier().enabled()) {
            sendPrefixedMsg(s,
                    "&cNo Discord, email, or personal Authenticator is configured. "
                            + "The protected action was blocked without freezing you.");
            return;
        }
        String code = hasTotp ? null : plugin.getApproval().generateCode();
        String totpSecret = hasTotp ? plugin.getAuthenticatorManager().getSecret(id) : null;
        var expiry = plugin.getApproval().defaultExpiry();

        UUID finalTargetId = targetId;
        String finalTargetName = targetName;
        PendingConfirmation pc = new PendingConfirmation(
                id, PendingConfirmation.Kind.UNFREEZE, code, totpSecret,
                "unfreeze " + targetName, "UNFREEZE", expiry,
                plugin.getApproval().maxAttempts(),
                pc2 -> executeUnfreeze(s, finalTargetId, finalTargetName),
                pc2 -> s.sendMessage(plugin.getConfigManager().msg("confirm_expired")));
        plugin.getApproval().register(pc);

        if (hasTotp) {
            String body = plugin.getConfigManager().msgPlain("unfreeze_notify_totp_plain",
                    "player", nameOf(s), "target", targetName, "time", Time.remaining(expiry));
            plugin.getNotifier().send("LockDown — UNFREEZE requested", body);
            s.sendMessage(plugin.getConfigManager().msg("confirm_prompt_totp", "time", Time.remaining(expiry)));
        } else {
            String body = plugin.getConfigManager().msgPlain("unfreeze_notify_plain",
                    "player", nameOf(s), "target", targetName, "code", code, "time", Time.remaining(expiry));
            plugin.getNotifier().send("LockDown — UNFREEZE requested", body);
            s.sendMessage(plugin.getConfigManager().msg("lockdown_code_sent", "time", Time.remaining(expiry)));
        }
    }

    private void executeUnfreeze(CommandSender s, UUID targetId, String targetName) {
        plugin.getLockdown().unfreeze(targetId);
        s.sendMessage(plugin.getConfigManager().msg("unfrozen_admin", "player", targetName));
        Player target = Bukkit.getPlayer(targetId);
        if (target != null) {
            target.sendMessage(plugin.getConfigManager().msg("unfrozen_player"));
        }
        plugin.getAudit().record(nameOf(s), senderId(s), "LockDown", "unfreeze " + targetName, null, true, AuditEntry.Result.CONFIRMED);
    }

    private boolean doConfirm(CommandSender s, String[] args) {
        if (args.length < 2) {
            sendPrefixedMsg(s, "&7Usage: /lockdown confirm <code>");
            return true;
        }
        UUID id = senderId(s);

        ApprovalResult res = confirmById(id, args[1]);
        switch (res) {
            case NONE -> s.sendMessage(plugin.getConfigManager().msg("confirm_none_pending"));
            case WRONG -> {
                PendingConfirmation pc = plugin.getApproval().get(id);
                int left = pc == null ? 0 : pc.attemptsLeft();
                s.sendMessage(plugin.getConfigManager().msg("confirm_wrong", "attempts", left));
            }
            case EXPIRED, FAILED -> s.sendMessage(plugin.getConfigManager().msg("confirm_expired"));
            default -> { }
        }
        return true;
    }

    private ApprovalResult confirmById(UUID id, String code) {
        if (id == null) return ApprovalResult.NONE;
        var result = plugin.getApproval().confirm(id, code);
        return switch (result) {
            case SUCCESS -> ApprovalResult.SUCCESS;
            case WRONG -> ApprovalResult.WRONG;
            case EXPIRED -> ApprovalResult.EXPIRED;
            case FAILED -> ApprovalResult.FAILED;
            case NONE -> ApprovalResult.NONE;
        };
    }

    private boolean doResend(CommandSender s) {
        if (!(s instanceof Player p)) {
            s.sendMessage(plugin.getConfigManager().msg("player_only"));
            return true;
        }
        UUID id = p.getUniqueId();

        long now = System.currentTimeMillis();
        if (now - resendCooldowns.getOrDefault(id, 0L) < 10000) {
            sendPrefixedMsg(s, "&cPlease wait a moment before requesting a new code.");
            return true;
        }
        resendCooldowns.put(id, now);

        boolean success = plugin.getApproval().resend(id);
        if (success) {
            boolean hasTotp = plugin.getAuthenticatorManager().hasAuth(id);
            PendingConfirmation pc = plugin.getApproval().get(id);
            var expiry = pc == null ? plugin.getApproval().defaultExpiry() : pc.expiresAt();
            if (hasTotp) {
                p.sendMessage(plugin.getConfigManager().msg("confirm_prompt_totp", "time", Time.remaining(expiry)));
            } else {
                p.sendMessage(plugin.getConfigManager().msg("confirm_prompt", "time", Time.remaining(expiry)));
            }
        } else {
            if (plugin.getLockdown().isFrozen(id)) {
                p.sendMessage(plugin.getConfigManager().msg("freeze_applied_failure"));
            } else {
                p.sendMessage(plugin.getConfigManager().msg("confirm_none_pending"));
            }
        }
        return true;
    }

    private void status(CommandSender s) {
        var lm = plugin.getLockdown();
        var cfg = plugin.getConfigManager().settings();
        sendPrefixedMsg(s, "&7Status:");
        sendMsg(s, " &8- &7Enabled: &f" + cfg.enabled);
        sendMsg(s, " &8- &7Global lockdown: &f" + lm.isGlobalLockdown());
        sendMsg(s, " &8- &7Lockdown mode: &f" + cfg.lockdown.mode);
        sendMsg(s, " &8- &7Frozen players: &f" + lm.frozenCount());
        sendMsg(s, " &8- &7Rules loaded: &f" + plugin.getRules().all().size());
    }

    private void help(CommandSender s, String label) {
        s.sendMessage(plugin.getConfigManager().msg("help_header"));
        List<String> visible = new ArrayList<>(List.of("confirm", "resend", "auth"));
        if (s.hasPermission(PERM_ADMIN)) {
            visible.addAll(List.of("reload", "scan", "gui", "rules", "lock", "unlock", "unfreeze", "status"));
        }
        for (String c : visible) {
            s.sendMessage(plugin.getConfigManager().msg("help_line",
                    "cmd", c, "desc", plugin.getConfigManager().msg("help_lines." + c)));
        }
    }

    private enum ApprovalResult {
        SUCCESS,
        WRONG,
        EXPIRED,
        FAILED,
        NONE
    }

    private static UUID senderId(CommandSender s) {
        return s instanceof Player p ? p.getUniqueId() : CONSOLE_UUID;
    }

    private static String nameOf(CommandSender s) {
        return s instanceof Player p ? p.getName() : "CONSOLE";
    }
}
