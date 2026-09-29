package ir.synix.lockdown.gui;

import ir.synix.lockdown.LockDownPlugin;
import ir.synix.lockdown.util.Colors;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Coordinates LockDown inventory GUI creation, navigation, and click handling.
 */
public final class GuiManager implements Listener {

    private final LockDownPlugin plugin;
    private final Map<UUID, LockGuiHolder> open = new ConcurrentHashMap<>();

    public GuiManager(LockDownPlugin plugin) {
        this.plugin = plugin;
    }

    public void openMain(Player p) {
        MainGui g = new MainGui();
        open.put(p.getUniqueId(), g);
        g.open(p, plugin);
    }

    public void openLogs(Player p) {
        LogsGui g = new LogsGui();
        open.put(p.getUniqueId(), g);
        g.open(p, plugin);
    }

    public void openRules(Player p) {
        RulesGui g = new RulesGui();
        open.put(p.getUniqueId(), g);
        g.open(p, plugin);
    }

    public void openStatus(Player p) {
        StatusGui g = new StatusGui();
        open.put(p.getUniqueId(), g);
        g.open(p, plugin);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        Inventory top = e.getView().getTopInventory();
        if (!(top.getHolder() instanceof LockGuiHolder holder)) return;
        e.setCancelled(true);
        if (e.getCurrentItem() == null || e.getCurrentItem().getType() == Material.AIR) return;

        // Ignore clicks on cosmetic fillers
        if (e.getCurrentItem().getType() == Material.BLACK_STAINED_GLASS_PANE) return;

        Consumer<LockGuiHolder> reopen = h -> {
            open.put(p.getUniqueId(), h);
            Bukkit.getScheduler().runTask(plugin, () -> h.open(p, plugin));
        };
        try {
            holder.onClick(e, plugin, reopen);
        } catch (Throwable t) {
            plugin.getLogger().warning("[LockDown] GUI error: " + t.getMessage());
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        open.remove(e.getPlayer().getUniqueId());
    }

    /** Creates an ItemStack and translates all legacy & hex colors inside name and lore */
    public static ItemStack item(Material mat, String name, List<String> lore) {
        ItemStack is = new ItemStack(mat);
        ItemMeta m = is.getItemMeta();
        if (m != null) {
            // Translates hex (&#FFFFFF) and legacy codes cleanly
            m.setDisplayName(Colors.colorize(name));
            if (lore != null) {
                List<String> coloredLore = new ArrayList<>();
                for (String line : lore) {
                    coloredLore.add(Colors.colorize(line));
                }
                m.setLore(coloredLore);
            }
            is.setItemMeta(m);
        }
        return is;
    }

    public static ItemStack filler() {
        return item(Material.BLACK_STAINED_GLASS_PANE, " ", null);
    }

    public static void fillBorder(Inventory inv) {
        ItemStack f = filler();
        int size = inv.getSize();
        for (int i = 0; i < 9; i++) inv.setItem(i, f);
        for (int i = size - 9; i < size; i++) inv.setItem(i, f);
        for (int i = 9; i < size - 9; i += 9) {
            inv.setItem(i, f);
            inv.setItem(i + 8, f);
        }
    }
}
