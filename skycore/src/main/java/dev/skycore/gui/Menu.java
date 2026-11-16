package dev.skycore.gui;

import dev.skycore.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Chest GUI. Clicks are always cancelled; slots may carry an action.
 * When {@code takeOnly} is set the player may take items out of the top inventory but never put any in
 * (used for minion / chunk hopper storage views).
 */
public class Menu implements InventoryHolder {
    private final Inventory inv;
    private final Map<Integer, Consumer<InventoryClickEvent>> actions = new HashMap<>();
    public Runnable onClose;
    public boolean takeOnly;

    public Menu(int rows, String title) {
        inv = Bukkit.createInventory(this, rows * 9, Text.mm(title));
    }

    @Override
    public Inventory getInventory() {
        return inv;
    }

    public Menu set(int slot, ItemStack item, Consumer<InventoryClickEvent> action) {
        inv.setItem(slot, item);
        if (action != null) actions.put(slot, action);
        else actions.remove(slot);
        return this;
    }

    public Menu set(int slot, ItemStack item) {
        return set(slot, item, null);
    }

    public void fill(Material pane) {
        ItemStack filler = icon(pane, " ", List.of());
        for (int i = 0; i < inv.getSize(); i++) if (inv.getItem(i) == null) inv.setItem(i, filler);
    }

    public void border(Material pane) {
        ItemStack filler = icon(pane, " ", List.of());
        int size = inv.getSize();
        for (int i = 0; i < size; i++) {
            int r = i / 9, c = i % 9;
            if (r == 0 || r == size / 9 - 1 || c == 0 || c == 8) if (inv.getItem(i) == null) inv.setItem(i, filler);
        }
    }

    public void open(Player p) {
        p.openInventory(inv);
    }

    void handle(InventoryClickEvent e) {
        Consumer<InventoryClickEvent> a = actions.get(e.getRawSlot());
        if (a != null) a.accept(e);
    }

    public static ItemStack icon(Material m, String name, List<String> lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.displayName(Text.item(name));
            meta.lore(Text.lore(lore));
            meta.addItemFlags(ItemFlag.values());
            it.setItemMeta(meta);
        }
        return it;
    }

    public static ItemStack icon(Material m, String name, List<String> lore, boolean glint) {
        ItemStack it = icon(m, name, lore);
        if (glint) {
            ItemMeta meta = it.getItemMeta();
            meta.setEnchantmentGlintOverride(true);
            it.setItemMeta(meta);
        }
        return it;
    }

    public static ItemStack back() {
        return icon(Material.ARROW, "<yellow>Back", List.of());
    }
}
