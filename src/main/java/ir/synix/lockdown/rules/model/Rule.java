package ir.synix.lockdown.rules.model;

import java.util.List;
import java.util.Objects;

/**
 * Runtime model for a configurable command-security rule.
 *
 * <p>Each rule stores command patterns, an optional permission hint, and separate
 * action plans for authorized senders, unauthorized senders, and confirmation
 * failure. Patterns use {@code *} for one token and {@code **} for one or more
 * remaining tokens.
 */
public final class Rule {

    private final String id;
    private final String plugin;
    private boolean enabled;
    private List<String> patterns;
    private String permissionHint;
    private ActionPlan onAuthorized;
    private ActionPlan onUnauthorized;
    private ActionPlan onFailure;

    public Rule(String id, String plugin) {
        this.id = Objects.requireNonNull(id, "id");
        this.plugin = plugin == null ? "Unknown" : plugin;
        this.enabled = false;
        this.patterns = List.of();
        this.permissionHint = null;
        this.onAuthorized = ActionPlan.empty();
        this.onUnauthorized = ActionPlan.empty();
        this.onFailure = ActionPlan.empty();
    }

    public String getId() {
        return id;
    }

    public String getPlugin() {
        return plugin;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public List<String> getPatterns() {
        return patterns;
    }

    public String getPermissionHint() {
        return permissionHint;
    }

    public ActionPlan getOnAuthorized() {
        return onAuthorized;
    }

    public ActionPlan getOnUnauthorized() {
        return onUnauthorized;
    }

    public ActionPlan getOnFailure() {
        return onFailure;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void setPatterns(List<String> patterns) {
        this.patterns = patterns == null ? List.of() : patterns;
    }

    public void setPermissionHint(String permissionHint) {
        this.permissionHint = permissionHint;
    }

    public void setOnAuthorized(ActionPlan onAuthorized) {
        this.onAuthorized = onAuthorized;
    }

    public void setOnUnauthorized(ActionPlan onUnauthorized) {
        this.onUnauthorized = onUnauthorized;
    }

    public void setOnFailure(ActionPlan onFailure) {
        this.onFailure = onFailure;
    }

    /** The form to apply given whether the sender was authorized. */
    public ActionPlan formFor(boolean authorized) {
        return authorized ? onAuthorized : onUnauthorized;
    }

    @Override
    public String toString() {
        return "Rule{" + id + " (plugin=" + plugin + ", enabled=" + enabled + ")}";
    }
}
