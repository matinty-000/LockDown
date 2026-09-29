package ir.synix.lockdown.gui;

import ir.synix.lockdown.LockDownPlugin;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Builds the runtime status GUI.
 */
public final class StatusGui extends LockGuiHolder {

    private static final int BACK = 40, CLOSE = 44, STATUS_SLOT = 22;

    @Override
    protected Inventory createInventory(LockDownPlugin plugin) {
        return Bukkit.createInventory(this, 45, plugin.getConfigManager().msg("gui_title_status"));
    }

    @Override
    protected void build(LockDownPlugin plugin, Inventory inv) {
        GuiManager.fillBorder(inv);

        // Setup status item in place
        inv.setItem(STATUS_SLOT, buildStatusItem(plugin));

        inv.setItem(BACK, GuiManager.item(Material.BOOK, plugin.getConfigManager().msg("gui_back_item"), null));
        inv.setItem(CLOSE, GuiManager.item(Material.BARRIER, plugin.getConfigManager().msg("gui_close_item"), null));
    }

    /** Permanently targets only the single status item instead of refreshing the entire GUI */
    private ItemStack buildStatusItem(LockDownPlugin plugin) {
        var s = plugin.getConfigManager().settings();
        var lines = plugin.getConfigManager().msgList("gui_status_line");
        List<String> lore = new ArrayList<>();
        for (String l : lines) {
            lore.add(l
                    .replace("%enabled%", String.valueOf(s.enabled))
                    .replace("%locked%", String.valueOf(plugin.getLockdown().isGlobalLockdown()))
                    .replace("%mode%", s.lockdown.mode)
                    .replace("%frozen%", String.valueOf(plugin.getLockdown().frozenCount()))
                    .replace("%rules%", String.valueOf(plugin.getRules().all().size()))
                    .replace("%discord%", s.discord.enabled() ? "yes" : "no")
                    .replace("%email%", (s.smtp.enabled() && !s.guardians.emails.isEmpty()) ? "yes" : "no"));
        }
        return GuiManager.item(Material.KNOWLEDGE_BOOK, "&fLockDown Status", lore);
    }

    @Override
    public void onClick(InventoryClickEvent e, LockDownPlugin plugin, Consumer<LockGuiHolder> reopen) {
        int slot = e.getRawSlot();
        if (slot == BACK) {
            reopen.accept(new MainGui());
        } else if (slot == CLOSE) {
            // Safe next-tick close!
            Bukkit.getScheduler().runTask(plugin, () -> e.getWhoClicked().closeInventory());
        } else if (slot == STATUS_SLOT) {
            // Update only the same status item permanently! No cursor flicker.
            inventory.setItem(STATUS_SLOT, buildStatusItem(plugin));
        }
    }
}
