package dev.skycore.items;

import dev.skycore.SkyCore;
import dev.skycore.gui.Menu;
import dev.skycore.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.EnumSet;
import java.util.Iterator;
import java.util.Set;

/**
 * Soulbound items can never leave the owner's own inventory (or ender chest):
 * no dropping, no containers, no item frames, no crafting, kept on death.
 */
public final class SoulboundListener implements Listener {
    private static final Set<InventoryType> ALLOWED_TOP = EnumSet.of(InventoryType.CRAFTING, InventoryType.ENDER_CHEST, InventoryType.CREATIVE);
    private static final Set<Material> INSERT_BLOCKS = EnumSet.of(Material.DECORATED_POT, Material.CHISELED_BOOKSHELF,
            Material.JUKEBOX, Material.LECTERN, Material.COMPOSTER, Material.CAMPFIRE, Material.SOUL_CAMPFIRE,
            Material.FLOWER_POT);

    private final SkyCore plugin;

    public SoulboundListener(SkyCore plugin) {
        this.plugin = plugin;
    }

    private static boolean sb(ItemStack i) {
        return SpecialItems.isSoulbound(i);
    }

    private void deny(Player p) {
        Text.msg(p, "<red>Soulbound items cannot be traded, stored or dropped.");
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        if (sb(e.getItemDrop().getItemStack())) {
            e.setCancelled(true);
            deny(e.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        Inventory top = e.getView().getTopInventory();
        if (top.getHolder() instanceof Menu) return;
        if (ALLOWED_TOP.contains(top.getType())) return;
        boolean clickedTop = e.getClickedInventory() != null && e.getClickedInventory().equals(top);
        boolean bad = false;
        switch (e.getAction()) {
            case MOVE_TO_OTHER_INVENTORY -> bad = !clickedTop && sb(e.getCurrentItem());
            case PLACE_ALL, PLACE_ONE, PLACE_SOME, SWAP_WITH_CURSOR -> bad = clickedTop && sb(e.getCursor());
            case HOTBAR_SWAP -> {
                if (clickedTop) {
                    ItemStack hb = e.getHotbarButton() >= 0 ? p.getInventory().getItem(e.getHotbarButton())
                            : p.getInventory().getItemInOffHand();
                    bad = sb(hb);
                }
            }
            case COLLECT_TO_CURSOR -> bad = false;
            default -> {}
        }
        if (e.getClick() == ClickType.DROP || e.getClick() == ClickType.CONTROL_DROP) bad |= sb(e.getCurrentItem());
        if (e.getAction() == InventoryAction.DROP_ALL_CURSOR || e.getAction() == InventoryAction.DROP_ONE_CURSOR) bad |= sb(e.getCursor());
        if (bad) {
            e.setCancelled(true);
            deny(p);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onCreativeOrPlayerDrop(InventoryClickEvent e) {
        // Dropping from the player's own inventory screen.
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if ((e.getAction() == InventoryAction.DROP_ALL_SLOT || e.getAction() == InventoryAction.DROP_ONE_SLOT) && sb(e.getCurrentItem())) {
            e.setCancelled(true);
            deny(p);
        }
        if (e.getSlotType() == InventoryType.SlotType.OUTSIDE && sb(e.getCursor())) {
            e.setCancelled(true);
            deny(p);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (top.getHolder() instanceof Menu || ALLOWED_TOP.contains(top.getType())) return;
        if (!sb(e.getOldCursor())) return;
        int topSize = top.getSize();
        for (int raw : e.getRawSlots()) {
            if (raw < topSize) {
                e.setCancelled(true);
                if (e.getWhoClicked() instanceof Player p) deny(p);
                return;
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onCraft(PrepareItemCraftEvent e) {
        for (ItemStack i : e.getInventory().getMatrix()) {
            if (sb(i) || SpecialItems.type(i) != null) {
                e.getInventory().setResult(null);
                return;
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityInteract(PlayerInteractEntityEvent e) {
        ItemStack hand = e.getPlayer().getInventory().getItem(e.getHand());
        if (sb(hand)) {
            e.setCancelled(true);
            deny(e.getPlayer());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onArmorStand(PlayerArmorStandManipulateEvent e) {
        if (sb(e.getPlayerItem())) {
            e.setCancelled(true);
            deny(e.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onInsert(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null) return;
        if (!sb(e.getItem())) return;
        if (INSERT_BLOCKS.contains(e.getClickedBlock().getType())) {
            e.setCancelled(true);
            deny(e.getPlayer());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent e) {
        if (SpecialItems.type(e.getItem()) != null) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent e) {
        if (e.getKeepInventory()) return;
        Iterator<ItemStack> it = e.getDrops().iterator();
        while (it.hasNext()) {
            ItemStack i = it.next();
            if (sb(i)) {
                e.getItemsToKeep().add(i);
                it.remove();
            }
        }
    }
}
