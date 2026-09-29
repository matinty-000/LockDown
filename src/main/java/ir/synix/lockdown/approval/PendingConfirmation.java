package ir.synix.lockdown.approval;

import ir.synix.lockdown.util.TotpUtil;

import java.time.Instant;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Represents a confirmation request together with its expiry, attempt budget, and completion callbacks.
 */
public final class PendingConfirmation {

    public enum Kind {
        COMMAND,
        LOCKDOWN_ENGAGE,
        LOCKDOWN_RELEASE,
        UNFREEZE
    }

    private final UUID id;
    private final UUID senderId;
    private final Kind kind;
    private String code;
    private final String totpSecret;
    private final String command;
    private final String ruleId;
    private Instant expiresAt;
    private final int maxAttempts;

    private int attempts;
    private boolean failureFired = false;
    private final Consumer<PendingConfirmation> onSuccess;
    private final Consumer<PendingConfirmation> onFailure;

    public PendingConfirmation(UUID senderId, Kind kind, String code, String totpSecret, String command, String ruleId,
                               Instant expiresAt, int maxAttempts,
                               Consumer<PendingConfirmation> onSuccess,
                               Consumer<PendingConfirmation> onFailure) {
        this.id = UUID.randomUUID();
        this.senderId = senderId;
        this.kind = kind;
        this.code = code;
        this.totpSecret = totpSecret;
        this.command = command;
        this.ruleId = ruleId;
        this.expiresAt = expiresAt;
        this.maxAttempts = maxAttempts;
        this.onSuccess = onSuccess;
        this.onFailure = onFailure;
    }

    public UUID id() {
        return id;
    }

    public UUID senderId() {
        return senderId;
    }

    public Kind kind() {
        return kind;
    }

    public String code() {
        return code;
    }

    public String totpSecret() {
        return totpSecret;
    }

    public String command() {
        return command;
    }

    public String ruleId() {
        return ruleId;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    public int attempts() {
        return attempts;
    }

    public synchronized void restoreAttempts(int attempts) {
        this.attempts = Math.max(0, Math.min(maxAttempts, attempts));
    }

    public int attemptsLeft() {
        return Math.max(0, maxAttempts - attempts);
    }

    public boolean isFailureFired() {
        return failureFired;
    }

    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }

    public synchronized void reset(String newCode, String newTotp, Instant newExpiry) {
        this.code = newCode;
        this.expiresAt = newExpiry;
        this.attempts = 0;
        this.failureFired = false;
    }

    public synchronized boolean submit(String guess) {
        if (totpSecret != null) {
            if (TotpUtil.verifyConfirmation(totpSecret, guess)) {
                return true;
            }
        } else {
            if (code != null && code.equalsIgnoreCase(guess)) {
                return true;
            }
        }
        attempts++;
        return false;
    }

    public void fireSuccess() {
        if (onSuccess != null) onSuccess.accept(this);
    }

    public void fireFailure() {
        if (!failureFired) {
            failureFired = true;
            if (onFailure != null) onFailure.accept(this);
        }
    }
}
