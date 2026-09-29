package ir.synix.lockdown;

import ir.synix.lockdown.admin.LockDownCommand;
import ir.synix.lockdown.approval.ApprovalService;
import ir.synix.lockdown.approval.AuthenticatorManager;
import ir.synix.lockdown.audit.AuditLogger;
import ir.synix.lockdown.command.CommandIndex;
import ir.synix.lockdown.command.CommandInterceptor;
import ir.synix.lockdown.config.ConfigManager;
import ir.synix.lockdown.gui.GuiManager;
import ir.synix.lockdown.lockdown.FreezeListener;
import ir.synix.lockdown.lockdown.LockdownManager;
import ir.synix.lockdown.notify.CompositeNotifier;
import ir.synix.lockdown.notify.DiscordWebhookNotifier;
import ir.synix.lockdown.notify.Notifier;
import ir.synix.lockdown.notify.SmtpNotifier;
import ir.synix.lockdown.rules.RuleEngine;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Main plugin bootstrap responsible for wiring LockDown services, listeners, commands, and lifecycle tasks.
 */
public final class LockDownPlugin extends JavaPlugin {

    private ConfigManager configManager;
    private RuleEngine ruleEngine;
    private CommandIndex commandIndex;
    private LockdownManager lockdownManager;
    private AuditLogger auditLogger;
    private ApprovalService approvalService;
    private AuthenticatorManager authenticatorManager;
    private Notifier notifier;
    private GuiManager guiManager;
    private FreezeListener freezeListener;

    private BukkitTask purgeTask;
    private final Map<String, AtomicInteger> consoleBypass = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        configManager = new ConfigManager(this);
        configManager.load();

        lockdownManager = new LockdownManager(this);
        auditLogger = new AuditLogger(this, configManager);
        auditLogger.init();
        approvalService = new ApprovalService(this);
        authenticatorManager = new AuthenticatorManager(this);

        rebuildNotifiers();

        ruleEngine = new RuleEngine(this, configManager);
        ruleEngine.reload();
        commandIndex = new CommandIndex(this, configManager);

        freezeListener = new FreezeListener(this, lockdownManager, configManager);
        CommandInterceptor interceptor = new CommandInterceptor(
                this, configManager, ruleEngine, lockdownManager, auditLogger, (CompositeNotifier) notifier);

        guiManager = new GuiManager(this);

        LockDownCommand cmd = new LockDownCommand(this);
        var pc = getCommand("lockdown");
        if (pc != null) {
            pc.setExecutor(cmd);
            pc.setTabCompleter(cmd);
        }

        Bukkit.getPluginManager().registerEvents(freezeListener, this);
        Bukkit.getPluginManager().registerEvents(interceptor, this);
        Bukkit.getPluginManager().registerEvents(guiManager, this);
        Bukkit.getPluginManager().registerEvents(cmd, this);

        purgeTask = Bukkit.getScheduler().runTaskTimer(this, new Runnable() {
            @Override
            public void run() {
                approvalService.purgeExpired();
            }
        }, 20L, 20L);

        lockdownManager.loadState();
        getLogger().info("[LockDown] Enabled. " + ruleEngine.all().size() + " rule(s) loaded.");
    }

    @Override
    public void onDisable() {
        if (purgeTask != null) purgeTask.cancel();
        if (lockdownManager != null) {
            lockdownManager.saveStateNow();
        }
    }

    public void reloadAll() {
        configManager.reload();
        rebuildNotifiers();
        ruleEngine.reload();
        auditLogger.init();
    }

    public void reloadRules() {
        ruleEngine.reload();
    }

    private void rebuildNotifiers() {
        List<Notifier> list = List.of(
                new DiscordWebhookNotifier(configManager.settings(), getLogger()),
                new SmtpNotifier(this, configManager.settings(), getLogger()));
        this.notifier = new CompositeNotifier(list);
    }

    public void addConsoleBypass(String command) {
        if (command != null) {
            consoleBypass.computeIfAbsent(command.toLowerCase(Locale.ROOT), k -> new AtomicInteger()).incrementAndGet();
        }
    }

    public boolean consumeConsoleBypass(String command) {
        if (command == null) return false;
        String key = command.toLowerCase(Locale.ROOT);
        AtomicInteger count = consoleBypass.get(key);
        if (count != null) {
            int val = count.decrementAndGet();
            if (val <= 0) consoleBypass.remove(key);
            return val >= 0;
        }
        return false;
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public RuleEngine getRules() {
        return ruleEngine;
    }

    public CommandIndex getCommandIndex() {
        return commandIndex;
    }

    public LockdownManager getLockdown() {
        return lockdownManager;
    }

    public AuditLogger getAudit() {
        return auditLogger;
    }

    public ApprovalService getApproval() {
        return approvalService;
    }

    public AuthenticatorManager getAuthenticatorManager() {
        return authenticatorManager;
    }

    public Notifier getNotifier() {
        return notifier;
    }

    public GuiManager getGui() {
        return guiManager;
    }

    public FreezeListener getFreezeListener() {
        return freezeListener;
    }
}
