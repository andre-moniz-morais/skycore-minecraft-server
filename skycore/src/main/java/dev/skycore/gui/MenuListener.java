package dev.skycore.gui;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;

public final class MenuListener implements Listener {

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!(top.getHolder() instanceof Menu menu)) return;
        boolean inTop = e.getRawSlot() < top.getSize();
        if (menu.takeOnly) {
            InventoryAction a = e.getAction();
            boolean allowed = inTop && (a == InventoryAction.PICKUP_ALL || a == InventoryAction.PICKUP_HALF
                    || a == InventoryAction.PICKUP_ONE || a == InventoryAction.PICKUP_SOME
                    || a == InventoryAction.MOVE_TO_OTHER_INVENTORY) && e.getCursor().isEmpty();
            boolean ownInv = !inTop && a != InventoryAction.MOVE_TO_OTHER_INVENTORY && a != InventoryAction.COLLECT_TO_CURSOR;
            if (!(allowed || ownInv)) e.setCancelled(true);
            if (inTop) menu.handle(e);
            return;
        }
        e.setCancelled(true);
        if (inTop) menu.handle(e);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(InventoryDragEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!(top.getHolder() instanceof Menu)) return;
        for (int s : e.getRawSlots()) if (s < top.getSize()) {
            e.setCancelled(true);
            return;
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (e.getInventory().getHolder() instanceof Menu menu && menu.onClose != null) menu.onClose.run();
    }
}
