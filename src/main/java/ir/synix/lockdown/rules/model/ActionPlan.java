package ir.synix.lockdown.rules.model;

/**
 * Defines the actions LockDown can apply after a command rule matches.
 *
 * <p>The same model is used for authorized, unauthorized, and confirmation-failure
 * branches loaded from rule configuration.
 */
public final class ActionPlan {

    private boolean log;
    private boolean notify;
    private boolean block;
    private boolean requireConfirmation;
    private boolean freeze;

    public ActionPlan() {
    }

    public ActionPlan(boolean log, boolean notify, boolean block, boolean requireConfirmation, boolean freeze) {
        this.log = log;
        this.notify = notify;
        this.block = block;
        this.requireConfirmation = requireConfirmation;
        this.freeze = freeze;
    }

    public boolean isLog() {
        return log;
    }

    public boolean isNotify() {
        return notify;
    }

    public boolean isBlock() {
        return block;
    }

    public boolean isRequireConfirmation() {
        return requireConfirmation;
    }

    public boolean isFreeze() {
        return freeze;
    }

    public void setLog(boolean log) {
        this.log = log;
    }

    public void setNotify(boolean notify) {
        this.notify = notify;
    }

    public void setBlock(boolean block) {
        this.block = block;
    }

    public void setRequireConfirmation(boolean requireConfirmation) {
        this.requireConfirmation = requireConfirmation;
    }

    public void setFreeze(boolean freeze) {
        this.freeze = freeze;
    }

    /** True when at least one action is enabled. */
    public boolean hasAnyAction() {
        return log || notify || block || requireConfirmation || freeze;
    }

    /** A plan with every action disabled; used as a default in generated files. */
    public static ActionPlan empty() {
        return new ActionPlan(false, false, false, false, false);
    }

    /** Builder-style convenience to keep rule construction readable. */
    public static ActionPlan of(boolean log, boolean notify, boolean block, boolean confirm, boolean freeze) {
        return new ActionPlan(log, notify, block, confirm, freeze);
    }

    @Override
    public String toString() {
        return "ActionPlan{log=" + log
                + ", notify=" + notify
                + ", block=" + block
                + ", confirm=" + requireConfirmation
                + ", freeze=" + freeze + '}';
    }
}
