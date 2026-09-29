package ir.synix.lockdown.notify;

import java.util.List;

/**
 * Fans a single {@link #send(String, String)} out to every configured notifier.
 * Failures in one notifier never prevent the others from running.
 */
public final class CompositeNotifier implements Notifier {

    private final List<Notifier> delegates;

    public CompositeNotifier(List<Notifier> delegates) {
        this.delegates = List.copyOf(delegates);
    }

    @Override
    public String name() {
        return "composite";
    }

    @Override
    public boolean enabled() {
        for (Notifier n : delegates) if (n.enabled()) return true;
        return false;
    }

    @Override
    public void send(String subject, String body) {
        for (Notifier n : delegates) {
            if (!n.enabled()) continue;
            try {
                n.send(subject, body);
            } catch (Throwable t) {
                System.err.println("[LockDown] Notifier '" + n.name() + "' failed to send alert: " + t.getMessage());
                t.printStackTrace();
            }
        }
    }

    public List<Notifier> delegates() {
        return delegates;
    }
}
