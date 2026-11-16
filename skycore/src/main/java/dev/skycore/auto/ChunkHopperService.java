package dev.skycore.auto;

import dev.skycore.SkyCore;
import dev.skycore.data.Island;
import dev.skycore.items.SpecialItems;
import dev.skycore.shop.Pricing;
import dev.skycore.util.ItemSerial;
import dev.skycore.util.Pos;
import dev.skycore.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Chunk hoppers: collect dropped items of their chunk into 54 virtual slots every 2 seconds.
 * If a sell chest is adjacent, the collected items are sold straight away.
 */
public final class ChunkHopperService implements Listener {
    private final SkyCore plugin;
    private final Map<String, Inventory> inventories = new HashMap<>();
    private static final BlockFace[] FACES = {BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};

    public static final class Holder implements InventoryHolder {
        final String key;
        final int island;
        Inventory inv;

        Holder(String key, int island) {
            this.key = key;
            this.island = island;
        }

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    public ChunkHopperService(SkyCore plugin) {
        this.plugin = plugin;
    }

    public boolean isChunkHopper(Block b) {
        Island i = plugin.islands.at(b);
        return i != null && i.chunkHoppers.containsKey(Pos.key(b));
    }

    private Inventory inv(Island i, Island.ChunkHopper h) {
        return inventories.computeIfAbsent(h.pos, k -> {
            Holder holder = new Holder(k, i.id);
            Inventory inv = Bukkit.createInventory(holder, plugin.settings.automation.getInt("chunk-hopper.slots", 54) / 9 * 9,
                    Text.mm("<dark_gray>Chunk Hopper"));
            holder.inv = inv;
            for (ItemStack s : ItemSerial.decodeAll(h.storage)) inv.addItem(s);
            return inv;
        });
    }

    private void save(Island i, Island.ChunkHopper h) {
        Inventory inv = inventories.get(h.pos);
        if (inv == null) return;
        h.storage = ItemSerial.encodeAll(Arrays.asList(inv.getContents()));
        plugin.data.dirty(i);
    }

    public boolean place(Player p, Island i, Block b) {
        int limit = plugin.settings.islandLevel(i.level).chunkHoppers();
        if (i.chunkHoppers.size() >= limit) {
            Text.msg(p, "<red>Chunk hopper limit reached (" + limit + ").");
            return false;
        }
        int cx = b.getX() >> 4, cz = b.getZ() >> 4;
        for (String k : i.chunkHoppers.keySet()) {
            Pos o = Pos.parse(k);
            if (o.world().equals(b.getWorld().getName()) && (o.x() >> 4) == cx && (o.z() >> 4) == cz) {
                Text.msg(p, "<red>Only one chunk hopper per chunk.");
                return false;
            }
        }
        Island.ChunkHopper h = new Island.ChunkHopper();
        h.pos = Pos.key(b);
        h.owner = p.getUniqueId();
        i.chunkHoppers.put(h.pos, h);
        plugin.data.dirty(i);
        Text.msg(p, "<green>Chunk hopper placed. <gray>It collects every dropped item in this chunk.");
        return true;
    }

    public void remove(Player p, Island i, Block b) {
        Island.ChunkHopper h = i.chunkHoppers.get(Pos.key(b));
        if (h == null) return;
        if (!p.getUniqueId().equals(h.owner) && !p.getUniqueId().equals(i.owner)) {
            Text.msg(p, "<red>Only the owner of this chunk hopper or the island owner can remove it.");
            return;
        }
        Inventory inv = inv(i, h);
        for (ItemStack s : inv.getContents()) if (s != null) b.getWorld().dropItemNaturally(b.getLocation(), s);
        inv.clear();
        new java.util.ArrayList<>(inv.getViewers()).forEach(v -> v.closeInventory());
        inventories.remove(h.pos);
        i.chunkHoppers.remove(h.pos);
        plugin.data.dirty(i);
        b.setType(Material.AIR);
        plugin.rewards.giveOrBox(h.owner == null ? p.getUniqueId() : h.owner, plugin.items.chunkHopper(h.owner == null ? p.getUniqueId() : h.owner, 1));
    }

    public void open(Player p, Island i, Block b) {
        Island.ChunkHopper h = i.chunkHoppers.get(Pos.key(b));
        if (h != null) p.openInventory(inv(i, h));
    }

    public void tick() {
        for (Island i : plugin.islands.active()) {
            for (Island.ChunkHopper h : i.chunkHoppers.values()) {
                Pos pos = Pos.parse(h.pos);
                if (!pos.isLoaded()) continue;
                Block b = pos.block();
                if (b.getType() != Material.HOPPER) continue;
                Inventory inv = inv(i, h);
                boolean changed = false;
                Chunk c = b.getChunk();
                for (Entity e : c.getEntities()) {
                    if (!(e instanceof Item item) || item.isDead() || item.getPickupDelay() > 1000) continue;
                    ItemStack s = item.getItemStack();
                    if (SpecialItems.isSoulbound(s)) continue;
                    Map<Integer, ItemStack> left = inv.addItem(s.clone());
                    if (left.isEmpty()) item.remove();
                    else item.setItemStack(left.values().iterator().next());
                    changed = true;
                }
                if (hasAdjacentSellChest(i, b)) {
                    Pricing.Sale sale = plugin.pricing.sellInventory(inv, i.owner, i, 1.0, true);
                    if (sale.total() > 0) {
                        plugin.sellChests.credit(i, sale.total(), sale.items(), "chunk_hopper");
                        changed = true;
                    }
                }
                if (changed) save(i, h);
            }
        }
    }

    private boolean hasAdjacentSellChest(Island i, Block b) {
        for (BlockFace f : FACES) if (i.sellChests.contains(Pos.key(b.getRelative(f)))) return true;
        return false;
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getInventory().getHolder() instanceof Holder h)) return;
        Island i = plugin.islands.get(h.island);
        if (i == null) return;
        Island.ChunkHopper ch = i.chunkHoppers.get(h.key);
        if (ch != null) save(i, ch);
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(InventoryMoveItemEvent e) {
        if (isCH(e.getSource()) || isCH(e.getDestination())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onHopperPickup(InventoryPickupItemEvent e) {
        if (isCH(e.getInventory())) e.setCancelled(true);
    }

    private boolean isCH(Inventory inv) {
        if (inv.getLocation() == null || inv.getType() != org.bukkit.event.inventory.InventoryType.HOPPER) return false;
        return isChunkHopper(inv.getLocation().getBlock());
    }

    public void saveAll() {
        for (Island i : plugin.data.islands.values())
            for (Island.ChunkHopper h : i.chunkHoppers.values()) if (inventories.containsKey(h.pos)) save(i, h);
    }
}
