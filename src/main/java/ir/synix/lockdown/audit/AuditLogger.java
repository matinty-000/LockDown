package ir.synix.lockdown.audit;

import ir.synix.lockdown.LockDownPlugin;
import ir.synix.lockdown.config.ConfigManager;
import ir.synix.lockdown.util.Time;

import org.bukkit.Bukkit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Writes audit events asynchronously and keeps a bounded in-memory history for the GUI.
 */
public final class AuditLogger {

    private final LockDownPlugin plugin;
    private final ConfigManager config;
    private final AtomicLong seq = new AtomicLong();
    private final Deque<AuditEntry> buffer = new ArrayDeque<>();
    private final ReentrantLock bufLock = new ReentrantLock();
    private final ReentrantLock fileLock = new ReentrantLock();

    private Path logFile;
    private int maxSize;

    public AuditLogger(LockDownPlugin plugin, ConfigManager config) {
        this.plugin = plugin;
        this.config = config;
    }

    public void init() {
        maxSize = Math.max(16, config.settings().audit.bufferSize);
        Path base = config.file().toPath().toAbsolutePath().normalize();
        Path candidate = base.resolve(config.settings().audit.file.replace('\\', '/')).normalize();
        if (!candidate.startsWith(base)) {
            plugin.getLogger().warning("[LockDown] Unsafe audit.file path blocked; using logs/lockdown.log instead.");
            candidate = base.resolve("logs/lockdown.log").normalize();
        }
        logFile = candidate;
        try {
            Files.createDirectories(logFile.getParent());
            if (!Files.exists(logFile)) Files.createFile(logFile);
        } catch (IOException e) {
            plugin.getLogger().warning("[LockDown] Could not open audit log: " + e.getMessage());
        }

        if (config.settings().mongodb.enabled()) {
            plugin.getLogger().warning(
                    "[LockDown] MongoDB sync is enabled in config, but this version does not include "
                            + "the MongoDB driver dependencies. Logs will only be saved locally in logs/lockdown.log.");
        }
    }

    public AuditEntry record(String sender, UUID senderId, String pluginName,
                             String command, String ruleId, boolean authorized, AuditEntry.Result result) {
        AuditEntry e = new AuditEntry(seq.incrementAndGet(), Instant.now(), sender, senderId,
                pluginName, command, ruleId, authorized, result);
        pushBuffer(e);
        appendFile(e);
        if (config.settings().debug) {
            plugin.getLogger().info("[LockDown-AUDIT] " + e);
        }
        return e;
    }

    public List<AuditEntry> recent() {
        bufLock.lock();
        try {
            List<AuditEntry> list = new ArrayList<>(buffer);
            Collections.reverse(list);
            return list;
        } finally {
            bufLock.unlock();
        }
    }

    public int maxBufferSize() {
        return maxSize;
    }

    private void pushBuffer(AuditEntry e) {
        bufLock.lock();
        try {
            buffer.addLast(e);
            while (buffer.size() > maxSize) buffer.removeFirst();
        } finally {
            bufLock.unlock();
        }
    }

    private void appendFile(AuditEntry e) {
        if (logFile == null) return;
        String line = "[" + Time.now() + "] id=" + e.id()
                + " sender=" + e.sender()
                + " plugin=" + e.plugin()
                + " cmd=\"" + e.command().replace("\"", "'") + "\""
                + " rule=" + (e.ruleId() == null ? "-" : e.ruleId())
                + " auth=" + e.authorized()
                + " result=" + e.result()
                + System.lineSeparator();

        Runnable writeTask = () -> {
            fileLock.lock();
            try {
                Files.writeString(logFile, line,
                        StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException ex) {
                plugin.getLogger().warning("[LockDown] Failed to write audit log: " + ex.getMessage());
            } finally {
                fileLock.unlock();
            }
        };

        if (plugin.isEnabled()) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, writeTask);
        } else {
            writeTask.run();
        }
    }
}
