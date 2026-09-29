package ir.synix.lockdown.gui;

import ir.synix.lockdown.LockDownPlugin;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;

import java.util.function.Consumer;

/**
 * Builds the main LockDown control GUI.
 */
public final class MainGui extends LockGuiHolder {

    private static final int LOGS = 11;
    private static final int RULES = 13;
    private static final int STATUS = 15;
    private static final int CLOSE = 22;

    @Override
    protected Inventory createInventory(LockDownPlugin plugin) {
        return Bukkit.createInventory(this, 27, plugin.getConfigManager().msg("gui_title_main"));
    }

    @Override
    protected void build(LockDownPlugin plugin, Inventory inv) {
        GuiManager.fillBorder(inv);
        inv.setItem(LOGS, GuiManager.item(Material.BOOK,
                plugin.getConfigManager().msg("gui_logs_item"),
                plugin.getConfigManager().msgList("gui_logs_lore")));
        inv.setItem(RULES, GuiManager.item(Material.WRITABLE_BOOK,
                plugin.getConfigManager().msg("gui_rules_item"),
                plugin.getConfigManager().msgList("gui_rules_lore")));
        inv.setItem(STATUS, GuiManager.item(Material.PAPER,
                plugin.getConfigManager().msg("gui_status_item"),
                plugin.getConfigManager().msgList("gui_status_lore")));
        inv.setItem(CLOSE, GuiManager.item(Material.BARRIER,
                plugin.getConfigManager().msg("gui_close_item"), null));
    }

    @Override
    public void onClick(InventoryClickEvent e, LockDownPlugin plugin, Consumer<LockGuiHolder> reopen) {
        int slot = e.getRawSlot();
        if (slot == LOGS)       reopen.accept(new LogsGui());
        else if (slot == RULES) reopen.accept(new RulesGui());
        else if (slot == STATUS) reopen.accept(new StatusGui());
        else if (slot == CLOSE) {
            Bukkit.getScheduler().runTask(plugin, () -> e.getWhoClicked().closeInventory());
        }
    }
}
