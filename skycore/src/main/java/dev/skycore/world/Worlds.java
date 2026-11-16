package dev.skycore.world;

import dev.skycore.SkyCore;
import org.bukkit.*;
import org.bukkit.block.Biome;

/** Creates and owns the three island worlds. */
public final class Worlds {
    private final SkyCore plugin;
    private World overworld, nether, end;

    public Worlds(SkyCore plugin) {
        this.plugin = plugin;
    }

    public void init() {
        overworld = create(plugin.settings.worldOverworld, World.Environment.NORMAL, Biome.PLAINS);
        nether = create(plugin.settings.worldNether, World.Environment.NETHER, Biome.NETHER_WASTES);
        end = create(plugin.settings.worldEnd, World.Environment.THE_END, Biome.THE_END);
    }

    @SuppressWarnings("deprecation")
    private World create(String name, World.Environment env, Biome biome) {
        World w = Bukkit.getWorld(name);
        if (w == null) {
            w = new WorldCreator(name).environment(env).generator(new VoidGenerator(biome))
                    .generateStructures(false).createWorld();
        }
        if (w == null) throw new IllegalStateException("Could not create world " + name);
        w.setGameRule(GameRules.FIRE_SPREAD_RADIUS_AROUND_PLAYER, 0);
        w.setGameRule(GameRules.MOB_GRIEFING, false);
        w.setGameRule(GameRules.PVP, false);
        w.setGameRule(GameRules.SPAWN_PHANTOMS, false);
        w.setGameRule(GameRules.SPAWN_WANDERING_TRADERS, false);
        w.setGameRule(GameRules.SPAWN_PATROLS, false);
        w.setGameRule(GameRules.SPAWN_WARDENS, false);
        w.setGameRule(GameRules.SPAWNER_BLOCKS_WORK, false);
        w.setGameRule(GameRules.RESPAWN_RADIUS, 0);
        w.setSpawnLocation(0, 101, 0);
        return w;
    }

    public World world(Dim d) {
        return switch (d) {
            case OVERWORLD -> overworld;
            case NETHER -> nether;
            case END -> end;
        };
    }

    public Dim dim(World w) {
        if (w == null) return null;
        if (w.equals(overworld)) return Dim.OVERWORLD;
        if (w.equals(nether)) return Dim.NETHER;
        if (w.equals(end)) return Dim.END;
        return null;
    }

    public boolean isIslandWorld(World w) {
        return dim(w) != null;
    }

    public World overworld() {
        return overworld;
    }

    public Location spawn() {
        var c = plugin.settings.config;
        return new Location(overworld, c.getDouble("spawn.x", 0.5), c.getDouble("spawn.y", 101), c.getDouble("spawn.z", 0.5),
                (float) c.getDouble("spawn.yaw", 0), 0);
    }

    public boolean isSpawnArea(Location l) {
        if (l.getWorld() == null || !l.getWorld().equals(overworld)) return false;
        int r = plugin.settings.config.getInt("spawn.protection-radius", 40);
        return Math.abs(l.getX()) <= r && Math.abs(l.getZ()) <= r;
    }
}
