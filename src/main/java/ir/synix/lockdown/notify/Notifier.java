package ir.synix.lockdown.notify;

/** A way to deliver a plain-text alert or confirmation code out-of-game. */
public interface Notifier {

    /** Human-readable name, for status display. */
    String name();

    /** Whether this notifier is configured and ready to send. */
    boolean enabled();

    /**
     * Deliver a plain-text message (subject + body) to the configured
     * guardians. Implementations must be non-blocking on the calling thread.
     */
    void send(String subject, String body);
}
