package dev.skycore.island;

import dev.skycore.SkyCore;
import dev.skycore.config.Settings;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.block.data.Orientable;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.Axis;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/** Builds the starter island, the Nether / End mini-islands and the spawn platform. */
public final class IslandBuilder {
    private final SkyCore plugin;

    public IslandBuilder(SkyCore plugin) {
        this.plugin = plugin;
    }

    private static void set(World w, int x, int y, int z, Material m) {
        w.getBlockAt(x, y, z).setType(m, false);
    }

    private void platform(World w, int x, int y, int z, int r, Material top, Material fill) {
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (Math.abs(dx) == r && Math.abs(dz) == r) continue;
                set(w, x + dx, y - 3, z + dz, fill);
                set(w, x + dx, y - 2, z + dz, fill);
                set(w, x + dx, y - 1, z + dz, top);
            }
        }
    }

    /**
     * A classic hole generator: lava source, gap (block forms here), hole with falling water, water source.
     * Walls are the surrounding platform.
     */
    private void generator(World w, int x, int y, int z) {
        int gz = z + 2;
        w.getBlockAt(x - 1, y - 1, gz).setType(Material.AIR, false);
        w.getBlockAt(x, y - 1, gz).setType(Material.AIR, false);
        w.getBlockAt(x, y - 2, gz).setType(Material.AIR, false);
        w.getBlockAt(x - 2, y - 1, gz).setType(Material.LAVA, true);
        w.getBlockAt(x + 1, y - 1, gz).setType(Material.WATER, true);
    }

    public void overworld(Location c) {
        World w = c.getWorld();
        int x = c.getBlockX(), y = c.getBlockY(), z = c.getBlockZ();
        platform(w, x, y, z, 4, Material.GRASS_BLOCK, Material.DIRT);
        // a sand patch for sugar cane / cactus
        set(w, x + 3, y - 1, z - 3, Material.SAND);
        tree(w, x - 2, y, z - 2);
        Block chestBlock = w.getBlockAt(x + 2, y, z - 2);
        chestBlock.setType(Material.CHEST, false);
        if (chestBlock.getState() instanceof Chest chest) {
            for (ItemStack it : starterItems()) chest.getBlockInventory().addItem(it);
        }
        generator(w, x, y, z);
    }

    private List<ItemStack> starterItems() {
        List<String> raw = plugin.settings.config.getStringList("islands.starter-chest");
        if (raw.isEmpty()) raw = List.of("ICE:2", "LAVA_BUCKET:1", "BONE_MEAL:8", "OAK_SAPLING:2", "SUGAR_CANE:1",
                "CACTUS:1", "MELON_SEEDS:1", "PUMPKIN_SEEDS:1", "WHEAT_SEEDS:4", "BREAD:8", "SAND:2");
        return raw.stream().map(s -> {
            String[] p = s.split(":");
            return new ItemStack(Settings.mat(p[0]), p.length > 1 ? Integer.parseInt(p[1]) : 1);
        }).toList();
    }

    private void tree(World w, int x, int y, int z) {
        for (int dy = -2; dy <= 2; dy++) {
            int r = dy <= 0 ? 2 : 1;
            for (int dx = -r; dx <= r; dx++)
                for (int dz = -r; dz <= r; dz++) {
                    if (dy == 2 && Math.abs(dx) == 1 && Math.abs(dz) == 1) continue;
                    Block b = w.getBlockAt(x + dx, y + 4 + dy, z + dz);
                    if (!b.getType().isAir()) continue;
                    b.setType(Material.OAK_LEAVES, false);
                    if (b.getBlockData() instanceof Leaves leaves) {
                        leaves.setPersistent(false);
                        leaves.setDistance(Math.min(7, Math.max(1, Math.abs(dx) + Math.abs(dz) + Math.abs(Math.min(dy, 0)))));
                        b.setBlockData(leaves, false);
                    }
                }
        }
        for (int dy = 0; dy < 5; dy++) set(w, x, y + dy, z, Material.OAK_LOG);
    }

    public void nether(Location c) {
        World w = c.getWorld();
        int x = c.getBlockX(), y = c.getBlockY(), z = c.getBlockZ();
        platform(w, x, y, z, 3, Material.NETHERRACK, Material.NETHERRACK);
        set(w, x + 2, y - 1, z - 1, Material.SOUL_SAND);
        // return portal on the north edge (axis X)
        int pz = z - 3;
        for (int dx = -1; dx <= 2; dx++) {
            set(w, x + dx, y - 1, pz, Material.OBSIDIAN);
            set(w, x + dx, y + 3, pz, Material.OBSIDIAN);
        }
        for (int dy = 0; dy <= 2; dy++) {
            set(w, x - 1, y + dy, pz, Material.OBSIDIAN);
            set(w, x + 2, y + dy, pz, Material.OBSIDIAN);
            for (int dx = 0; dx <= 1; dx++) {
                Block b = w.getBlockAt(x + dx, y + dy, pz);
                b.setType(Material.NETHER_PORTAL, false);
                if (b.getBlockData() instanceof Orientable o) {
                    o.setAxis(Axis.X);
                    b.setBlockData(o, false);
                }
            }
        }
        generator(w, x, y, z);
    }

    public void end(Location c) {
        World w = c.getWorld();
        int x = c.getBlockX(), y = c.getBlockY(), z = c.getBlockZ();
        platform(w, x, y, z, 3, Material.END_STONE, Material.END_STONE);
        // return portal: a single end portal block in the floor
        set(w, x + 2, y - 1, z - 2, Material.END_PORTAL);
        set(w, x + 2, y - 2, z - 2, Material.OBSIDIAN);
        generator(w, x, y, z);
    }

    public void spawn(Location c) {
        World w = c.getWorld();
        int x = c.getBlockX(), y = c.getBlockY(), z = c.getBlockZ();
        int r = 10;
        for (int dx = -r; dx <= r; dx++)
            for (int dz = -r; dz <= r; dz++) {
                double d = Math.sqrt(dx * dx + dz * dz);
                if (d > r + 0.5) continue;
                Material m = d > r - 1 ? Material.POLISHED_DEEPSLATE : ((dx + dz) % 2 == 0 ? Material.SMOOTH_QUARTZ : Material.QUARTZ_BRICKS);
                set(w, x + dx, y - 1, z + dz, m);
                if (d > r - 3) set(w, x + dx, y - 2, z + dz, Material.POLISHED_DEEPSLATE);
            }
        for (int[] p : new int[][]{{7, 7}, {-7, 7}, {7, -7}, {-7, -7}}) {
            set(w, x + p[0], y - 1, z + p[1], Material.GLOWSTONE);
            set(w, x + p[0], y, z + p[1], Material.LANTERN);
        }
        set(w, x, y - 1, z, Material.SEA_LANTERN);
    }
}
