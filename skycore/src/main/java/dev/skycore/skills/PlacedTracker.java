package dev.skycore.skills;

import dev.skycore.SkyCore;
import dev.skycore.util.Keys;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.WorldSaveEvent;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;

/**
 * Remembers which skill-relevant blocks were placed by players, so they never give XP or Roses.
 * Stored per chunk in the chunk's persistent data (no database I/O), cached while loaded.
 */
public final class PlacedTracker implements Listener {
    private final SkyCore plugin;
    private final Set<Material> tracked = EnumSet.noneOf(Material.class);
    private final Map<String, Map<Long, Set<Integer>>> cache = new HashMap<>();
    private final Set<String> dirty = new HashSet<>();

    public PlacedTracker(SkyCore plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        tracked.clear();
        tracked.addAll(plugin.settings.miningXp.keySet());
        for (var lv : plugin.settings.gens.values())
            for (var g : lv.values()) tracked.addAll(g.blocks().keySet());
        tracked.addAll(List.of(Material.PUMPKIN, Material.MELON, Material.SUGAR_CANE, Material.CACTUS, Material.BAMBOO, Material.GLOWSTONE));
    }

    public boolean isTracked(Material m) {
        return tracked.contains(m);
    }

    private static int pack(int x, int y, int z) {
        return ((y + 2048) << 8) | ((x & 15) << 4) | (z & 15);
    }

    private static long chunkKey(int cx, int cz) {
        return ((long) cz << 32) | (cx & 0xFFFFFFFFL);
    }

    private Set<Integer> set(World w, int cx, int cz, boolean create) {
        Map<Long, Set<Integer>> perWorld = cache.computeIfAbsent(w.getName(), k -> new HashMap<>());
        long key = chunkKey(cx, cz);
        Set<Integer> s = perWorld.get(key);
        if (s != null) return s;
        if (!w.isChunkLoaded(cx, cz)) return create ? new HashSet<>() : Set.of();
        Chunk c = w.getChunkAt(cx, cz);
        int[] arr = c.getPersistentDataContainer().get(Keys.PLACED, PersistentDataType.INTEGER_ARRAY);
        s = new HashSet<>();
        if (arr != null) for (int v : arr) s.add(v);
        perWorld.put(key, s);
        return s;
    }

    public boolean isPlaced(Block b) {
        if (!tracked.contains(b.getType())) return false;
        return set(b.getWorld(), b.getX() >> 4, b.getZ() >> 4, false).contains(pack(b.getX(), b.getY(), b.getZ()));
    }

    public void mark(Block b) {
        Set<Integer> s = set(b.getWorld(), b.getX() >> 4, b.getZ() >> 4, true);
        if (s.add(pack(b.getX(), b.getY(), b.getZ()))) markDirty(b.getWorld(), b.getX() >> 4, b.getZ() >> 4);
    }

    public void unmark(Block b) {
        Map<Long, Set<Integer>> perWorld = cache.get(b.getWorld().getName());
        Set<Integer> s = set(b.getWorld(), b.getX() >> 4, b.getZ() >> 4, false);
        if (s.isEmpty() && (perWorld == null || !perWorld.containsKey(chunkKey(b.getX() >> 4, b.getZ() >> 4)))) return;
        if (s.remove(pack(b.getX(), b.getY(), b.getZ()))) markDirty(b.getWorld(), b.getX() >> 4, b.getZ() >> 4);
    }

    private void markDirty(World w, int cx, int cz) {
        dirty.add(w.getName() + ";" + cx + ";" + cz);
    }

    private void write(World w, int cx, int cz) {
        Set<Integer> s = cache.getOrDefault(w.getName(), Map.of()).get(chunkKey(cx, cz));
        if (s == null || !w.isChunkLoaded(cx, cz)) return;
        Chunk c = w.getChunkAt(cx, cz);
        if (s.isEmpty()) c.getPersistentDataContainer().remove(Keys.PLACED);
        else c.getPersistentDataContainer().set(Keys.PLACED, PersistentDataType.INTEGER_ARRAY,
                s.stream().mapToInt(Integer::intValue).toArray());
    }

    public void flushAll() {
        for (String d : dirty) {
            String[] p = d.split(";");
            World w = Bukkit.getWorld(p[0]);
            if (w != null) write(w, Integer.parseInt(p[1]), Integer.parseInt(p[2]));
        }
        dirty.clear();
    }

    // ------------------------------------------------------------------ events
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        Block b = e.getBlockPlaced();
        if (plugin.worlds.isIslandWorld(b.getWorld()) && tracked.contains(b.getType())) mark(b);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        if (!plugin.worlds.isIslandWorld(b.getWorld())) return;
        Bukkit.getScheduler().runTask(plugin, () -> unmark(b));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent e) {
        move(e.getBlocks(), e.getDirection());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent e) {
        move(e.getBlocks(), e.getDirection());
    }

    private void move(List<Block> blocks, org.bukkit.block.BlockFace dir) {
        if (blocks.isEmpty() || !plugin.worlds.isIslandWorld(blocks.getFirst().getWorld())) return;
        List<Block> marked = new ArrayList<>();
        for (Block b : blocks) if (isPlaced(b)) marked.add(b);
        for (Block b : marked) unmark(b);
        for (Block b : marked) mark(b.getRelative(dir));
    }

    @EventHandler
    public void onUnload(ChunkUnloadEvent e) {
        World w = e.getWorld();
        String k = w.getName() + ";" + e.getChunk().getX() + ";" + e.getChunk().getZ();
        if (dirty.remove(k)) write(w, e.getChunk().getX(), e.getChunk().getZ());
        Map<Long, Set<Integer>> perWorld = cache.get(w.getName());
        if (perWorld != null) perWorld.remove(chunkKey(e.getChunk().getX(), e.getChunk().getZ()));
    }

    @EventHandler
    public void onSave(WorldSaveEvent e) {
        flushAll();
    }
}
