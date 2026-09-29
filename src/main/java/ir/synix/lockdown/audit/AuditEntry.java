package ir.synix.lockdown.audit;

import java.time.Instant;
import java.util.UUID;

/** A single audited interception or lockdown event, kept for the GUI + log. */
public final class AuditEntry {

    public enum Result { ALLOWED, BLOCKED, FROZEN, AWAITING_CONFIRM, LOCKDOWN_BLOCK, CONFIRMED, FAILED }

    private final long id;
    private final Instant time;
    private final String sender;        // player name or "CONSOLE"
    private final UUID senderId;        // null for console
    private final String plugin;
    private final String command;
    private final String ruleId;
    private final boolean authorized;
    private final Result result;

    public AuditEntry(long id, Instant time, String sender, UUID senderId, String plugin,
                      String command, String ruleId, boolean authorized, Result result) {
        this.id = id;
        this.time = time;
        this.sender = sender;
        this.senderId = senderId;
        this.plugin = plugin;
        this.command = command;
        this.ruleId = ruleId;
        this.authorized = authorized;
        this.result = result;
    }

    public long id() {
        return id;
    }

    public Instant time() {
        return time;
    }

    public String sender() {
        return sender;
    }

    public UUID senderId() {
        return senderId;
    }

    public String plugin() {
        return plugin;
    }

    public String command() {
        return command;
    }

    public String ruleId() {
        return ruleId;
    }

    public boolean authorized() {
        return authorized;
    }

    public Result result() {
        return result;
    }

    @Override
    public String toString() {
        return "[" + time + "] " + sender + " (" + (authorized ? "auth" : "unauth") + ") "
                + "/" + command + " -> " + result + (ruleId == null ? "" : " [" + ruleId + "]");
    }
}
