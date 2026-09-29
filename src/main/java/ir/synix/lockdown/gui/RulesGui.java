package ir.synix.lockdown.gui;

import ir.synix.lockdown.LockDownPlugin;
import ir.synix.lockdown.rules.model.ActionPlan;
import ir.synix.lockdown.rules.model.Rule;
import ir.synix.lockdown.util.Colors;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Builds the paginated rule-inspection GUI.
 */
public final class RulesGui extends LockGuiHolder {

    private static final int PAGE_SIZE = 36;
    private static final int PREV = 45, BACK = 48, CLOSE = 49, NEXT = 53;
    private int totalPages = 1;

    @Override
    protected Inventory createInventory(LockDownPlugin plugin) {
        return Bukkit.createInventory(this, 54,
                Colors.colorize(plugin.getConfigManager().msg("gui_title_rules") + " &7p." + (page + 1)));
    }

    @Override
    protected void build(LockDownPlugin plugin, Inventory inv) {
        ItemStack filler = GuiManager.filler();
        for (int i = 36; i < 54; i++) inv.setItem(i, filler);

        List<Rule> all = plugin.getRules().all();
        int from = page * PAGE_SIZE;
        for (int i = 0; i < PAGE_SIZE; i++) {
            int idx = from + i;
            if (idx >= all.size()) break;
            Rule r = all.get(idx);
            Material mat = r.isEnabled() ? Material.LIME_DYE : Material.GRAY_DYE;
            inv.setItem(i, GuiManager.item(mat,
                    (r.isEnabled() ? "&a" : "&7") + r.getId(),
                    List.of(
                            "&7Plugin: &f" + r.getPlugin(),
                            "&7Enabled: &f" + r.isEnabled(),
                            "&7Patterns: &f" + String.join(", ", r.getPatterns()),
                            "&7Permission: &f" + (r.getPermissionHint() == null ? "-" : r.getPermissionHint()),
                            "&7Auth form: &f" + shortPlan(r.getOnAuthorized()),
                            "&7Unauth form: &f" + shortPlan(r.getOnUnauthorized()))));
        }

        totalPages = Math.max(1, (all.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        if (page > 0) inv.setItem(PREV, GuiManager.item(Material.ARROW, plugin.getConfigManager().msg("gui_prev_item"), null));
        if (page + 1 < totalPages) inv.setItem(NEXT, GuiManager.item(Material.ARROW, plugin.getConfigManager().msg("gui_next_item"), null));
        inv.setItem(BACK, GuiManager.item(Material.BOOK, plugin.getConfigManager().msg("gui_back_item"), null));
        inv.setItem(CLOSE, GuiManager.item(Material.BARRIER, plugin.getConfigManager().msg("gui_close_item"), null));
    }

    private static String shortPlan(ActionPlan p) {
        if (p == null) return "-";
        List<String> on = new ArrayList<>();
        if (p.isLog()) on.add("log");
        if (p.isNotify()) on.add("notify");
        if (p.isBlock()) on.add("block");
        if (p.isRequireConfirmation()) on.add("confirm");
        if (p.isFreeze()) on.add("freeze");
        return on.isEmpty() ? "none" : String.join("+", on);
    }

    @Override
    public void onClick(InventoryClickEvent e, LockDownPlugin plugin, Consumer<LockGuiHolder> reopen) {
        int slot = e.getRawSlot();
        if (slot == NEXT && page + 1 < totalPages) { page++; this.refresh(plugin); }
        else if (slot == PREV && page > 0) { page--; this.refresh(plugin); }
        else if (slot == BACK) { reopen.accept(new MainGui()); }
        else if (slot == CLOSE) {
            Bukkit.getScheduler().runTask(plugin, () -> e.getWhoClicked().closeInventory());
        }
    }
}
