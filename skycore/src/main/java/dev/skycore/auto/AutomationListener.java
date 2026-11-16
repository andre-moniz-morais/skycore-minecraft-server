package dev.skycore.auto;

import dev.skycore.SkyCore;
import dev.skycore.data.Island;
import dev.skycore.items.SpecialItems;
import dev.skycore.util.Pos;
import dev.skycore.util.Text;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Chest;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.UUID;

/** Placement, removal and interaction for every SkyCore block (minions, chests, hoppers, spawners, crops). */
public final class AutomationListener implements Listener {
    private static final BlockFace[] SIDES = {BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};
    private final SkyCore plugin;

    public AutomationListener(SkyCore plugin) {
        this.plugin = plugin;
    }

    /** Any SkyCore-managed block at this position. */
    public boolean isSpecial(Block b) {
        Island i = plugin.islands.at(b);
        if (i == null) return false;
        String k = Pos.key(b);
        return i.minions.containsKey(k) || i.spawners.containsKey(k) || i.sellChests.contains(k)
                || i.chunkHoppers.containsKey(k) || i.shops.contains(k) || plugin.crops.isCrop(b);
    }

    private boolean specialChestNear(Island i, Block b) {
        for (BlockFace f : SIDES) {
            String k = Pos.key(b.getRelative(f));
            if (i.sellChests.contains(k) || i.shops.contains(k)) return true;
        }
        return false;
    }

    private boolean anyChestNear(Block b) {
        for (BlockFace f : SIDES) {
            Material m = b.getRelative(f).getType();
            if (m == Material.CHEST || m == Material.TRAPPED_CHEST) return true;
        }
        return false;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        Block b = e.getBlockPlaced();
        if (!plugin.worlds.isIslandWorld(b.getWorld())) return;
        Player p = e.getPlayer();
        ItemStack item = e.getItemInHand();
        String type = SpecialItems.type(item);
        Island i = plugin.islands.at(b);
        if (type == null) {
            if (i != null && (b.getType() == Material.CHEST || b.getType() == Material.TRAPPED_CHEST) && specialChestNear(i, b)) {
                e.setCancelled(true);
                Text.msg(p, "<red>You can't place a chest next to a sell chest or shop chest.");
            }
            if (b.getType() == Material.SPAWNER) {
                e.setCancelled(true);
                Text.msg(p, "<red>Only SkyCore spawners can be placed. Buy them in /shop.");
            }
            return;
        }
        if (i == null) {
            e.setCancelled(true);
            return;
        }
        UUID bound = SpecialItems.soulboundTo(item);
        if (bound != null && !bound.equals(p.getUniqueId())) {
            e.setCancelled(true);
            Text.msg(p, "<red>This item is soulbound to another player.");
            return;
        }
        boolean ok = switch (type) {
            case SpecialItems.MINION -> plugin.minions.place(p, i, b, item);
            case SpecialItems.SELL_CHEST -> placeSellChest(p, i, b);
            case SpecialItems.SHOP_CHEST -> !anyChestNearOrWarn(p, b) && plugin.playerShops.place(p, i, b);
            case SpecialItems.CHUNK_HOPPER -> plugin.chunkHoppers.place(p, i, b);
            case SpecialItems.SPAWNER -> plugin.spawners.place(p, i, b, item);
            case SpecialItems.SEED -> plugin.crops.place(p, i, b, item);
            default -> false;
        };
        if (!ok) e.setCancelled(true);
    }

    private boolean anyChestNearOrWarn(Player p, Block b) {
        if (anyChestNear(b)) {
            Text.msg(p, "<red>Special chests can't touch other chests.");
            return true;
        }
        return false;
    }

    private boolean placeSellChest(Player p, Island i, Block b) {
        if (anyChestNearOrWarn(p, b)) return false;
        int limit = plugin.settings.islandLevel(i.level).sellChests();
        if (i.sellChests.size() >= limit) {
            Text.msg(p, "<red>Sell chest limit reached (" + limit + "). Upgrade your island level for more.");
            return false;
        }
        i.sellChests.add(Pos.key(b));
        plugin.data.dirty(i);
        Text.msg(p, "<green>Sell chest placed. <gray>Its contents are sold every "
                + plugin.settings.config.getInt("automation.sell-chest-interval-seconds", 5) + "s.");
        return true;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        if (!plugin.worlds.isIslandWorld(b.getWorld())) return;
        Island i = plugin.islands.at(b);
        if (i == null) return;
        Player p = e.getPlayer();
        String k = Pos.key(b);
        Island.Minion m = i.minions.get(k);
        if (m != null) {
            e.setCancelled(true);
            plugin.minions.pickup(p, i, m);
            return;
        }
        if (i.spawners.containsKey(k)) {
            e.setCancelled(true);
            Text.msg(p, "<yellow>Sneak + right-click the spawner with an empty hand to pick it up.");
            return;
        }
        if (i.chunkHoppers.containsKey(k)) {
            e.setCancelled(true);
            plugin.chunkHoppers.remove(p, i, b);
            return;
        }
        if (i.sellChests.contains(k)) {
            e.setCancelled(true);
            dropContents(b);
            i.sellChests.remove(k);
            plugin.data.dirty(i);
            b.setType(Material.AIR);
            giveOrDrop(p, plugin.items.sellChest(1));
            return;
        }
        if (i.shops.contains(k)) {
            e.setCancelled(true);
            plugin.playerShops.breakShop(p, i, b);
        }
    }

    private void dropContents(Block b) {
        if (b.getState() instanceof Chest c) {
            for (ItemStack s : c.getBlockInventory().getContents())
                if (s != null) b.getWorld().dropItemNaturally(b.getLocation().add(0.5, 0.5, 0.5), s);
            c.getBlockInventory().clear();
        }
    }

    private void giveOrDrop(Player p, ItemStack it) {
        p.getInventory().addItem(it).values().forEach(l -> p.getWorld().dropItemNaturally(p.getLocation(), l));
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND) return;
        Block b = e.getClickedBlock();
        if (b == null || !plugin.worlds.isIslandWorld(b.getWorld())) return;
        Island i = plugin.islands.at(b);
        if (i == null) return;
        Player p = e.getPlayer();
        String k = Pos.key(b);
        if (i.shops.contains(k)) {
            e.setCancelled(true);
            plugin.playerShops.interact(p, i, b);
            return;
        }
        if (!i.isMember(p.getUniqueId()) && !p.hasPermission("skycore.bypass")) return;
        Island.Minion m = i.minions.get(k);
        if (m != null) {
            e.setCancelled(true);
            plugin.minions.open(p, i, m);
            return;
        }
        Island.SpawnerData s = i.spawners.get(k);
        if (s != null) {
            e.setCancelled(true);
            plugin.spawners.interact(p, i, b, s);
            return;
        }
        if (i.chunkHoppers.containsKey(k)) {
            e.setCancelled(true);
            plugin.chunkHoppers.open(p, i, b);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent e) {
        if (touches(e.getBlocks())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent e) {
        if (touches(e.getBlocks())) e.setCancelled(true);
    }

    private boolean touches(List<Block> blocks) {
        for (Block b : blocks) if (isSpecial(b)) return true;
        return false;
    }

    @EventHandler(ignoreCancelled = true)
    public void onFlow(BlockFromToEvent e) {
        Block to = e.getToBlock();
        if (to.getType() == Material.PLAYER_HEAD || to.getType() == Material.PLAYER_WALL_HEAD) {
            if (isSpecial(to)) e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBurn(BlockBurnEvent e) {
        if (isSpecial(e.getBlock())) e.setCancelled(true);
    }
}
