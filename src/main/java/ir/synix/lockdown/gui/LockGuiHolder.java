package ir.synix.lockdown.gui;

import ir.synix.lockdown.LockDownPlugin;

import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.function.Consumer;

/**
 * Marker {@link InventoryHolder} that also knows how to render and handle a
 * specific screen. Using a custom holder lets the click listener route events
 * to the right screen without inspecting titles.
 *
 * <p>Subclasses build contents via {@link #build(LockDownPlugin)}, which
 * receives an inventory already sized and titled (and with this holder bound)
 * via {@link #createInventory(LockDownPlugin)}.
 */
public abstract class LockGuiHolder implements InventoryHolder {

    protected Inventory inventory;
    protected Player viewer;
    protected int page = 0;

    public void open(Player viewer, LockDownPlugin plugin) {
        this.viewer = viewer;
        this.inventory = createInventory(plugin);
        build(plugin, inventory);
        viewer.openInventory(inventory);
    }

    public Player viewer() {
        return viewer;
    }

    public int page() {
        return page;
    }

    public void setPage(int page) {
        this.page = page;
    }

    /** Re-render the existing inventory in place (same holder, no reopen). */
    public void refresh(LockDownPlugin plugin) {
        if (inventory == null || viewer == null || !viewer.isOnline()) return;
        inventory.clear();
        build(plugin, inventory);
    }

    /** Size + title + holder. Subclasses call {@code Bukkit.createInventory(this, size, title)}. */
    protected abstract Inventory createInventory(LockDownPlugin plugin);

    /** Fill the given (already-created) inventory for the current state. */
    protected abstract void build(LockDownPlugin plugin, Inventory inv);

    /** Handle a click. Cancel is applied by the manager before this is called. */
    public abstract void onClick(InventoryClickEvent event, LockDownPlugin plugin,
                                 Consumer<LockGuiHolder> reopen);

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    /** Helper to copy item-by-item (Inventory has no bulk set). */
    protected static void copyInto(Inventory src, Inventory dst) {
        dst.clear();
        ItemStack[] contents = src.getContents();
        for (int i = 0; i < contents.length && i < dst.getSize(); i++) {
            if (contents[i] != null) dst.setItem(i, contents[i]);
        }
    }
}
