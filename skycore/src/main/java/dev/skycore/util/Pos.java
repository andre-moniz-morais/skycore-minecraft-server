package dev.skycore.util;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;

/** Immutable block position with a compact string key "world;x;y;z". */
public record Pos(String world, int x, int y, int z) {

    public static Pos of(Block b) {
        return new Pos(b.getWorld().getName(), b.getX(), b.getY(), b.getZ());
    }

    public static Pos of(Location l) {
        return new Pos(l.getWorld().getName(), l.getBlockX(), l.getBlockY(), l.getBlockZ());
    }

    public static Pos parse(String key) {
        String[] s = key.split(";");
        return new Pos(s[0], Integer.parseInt(s[1]), Integer.parseInt(s[2]), Integer.parseInt(s[3]));
    }

    public String key() {
        return world + ";" + x + ";" + y + ";" + z;
    }

    public static String key(Block b) {
        return b.getWorld().getName() + ";" + b.getX() + ";" + b.getY() + ";" + b.getZ();
    }

    public World bukkitWorld() {
        return Bukkit.getWorld(world);
    }

    public Block block() {
        World w = bukkitWorld();
        return w == null ? null : w.getBlockAt(x, y, z);
    }

    public Location center() {
        return new Location(bukkitWorld(), x + 0.5, y + 0.5, z + 0.5);
    }

    public boolean isLoaded() {
        World w = bukkitWorld();
        return w != null && w.isChunkLoaded(x >> 4, z >> 4);
    }

    public long chunkKey() {
        return ((long) (z >> 4) << 32) | ((x >> 4) & 0xFFFFFFFFL);
    }

    public double distSq(Pos o) {
        if (!world.equals(o.world)) return Double.MAX_VALUE;
        double dx = x - o.x, dy = y - o.y, dz = z - o.z;
        return dx * dx + dy * dy + dz * dz;
    }
}
