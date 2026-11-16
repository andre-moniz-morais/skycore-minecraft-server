package dev.skycore.gen;

import dev.skycore.SkyCore;
import dev.skycore.config.Settings;
import dev.skycore.data.Island;
import dev.skycore.data.Profile;
import dev.skycore.util.Text;
import dev.skycore.util.Weighted;
import dev.skycore.world.Dim;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Island generators: every lava/water (or basalt) formation on an island becomes a block rolled from
 * the island's generator table for that dimension. Also End Portal Frame drops with pity timer, and
 * water placement on the Nether mini-island.
 */
public final class GeneratorService implements Listener {
    private static final Set<Material> FORMED = Set.of(Material.COBBLESTONE, Material.STONE, Material.BASALT);
    private final SkyCore plugin;
    private final Map<Dim, Map<Integer, Weighted<Material>>> tables = new EnumMap<>(Dim.class);

    public GeneratorService(SkyCore plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        tables.clear();
        for (var e : plugin.settings.gens.entrySet()) {
            Map<Integer, Weighted<Material>> m = new HashMap<>();
            for (var g : e.getValue().values()) m.put(g.level(), new Weighted<>(g.blocks()));
            tables.put(e.getKey(), m);
        }
    }

    public static int level(Island i, Dim d) {
        return switch (d) {
            case OVERWORLD -> i.genOverworld;
            case NETHER -> i.genNether;
            case END -> i.genEnd;
        };
    }

    public static void setLevel(Island i, Dim d, int l) {
        switch (d) {
            case OVERWORLD -> i.genOverworld = l;
            case NETHER -> i.genNether = l;
            case END -> i.genEnd = l;
        }
    }

    /** True if the island generator for that dimension currently produces this block. */
    public boolean produces(Island i, Dim d, Material m) {
        Settings.GenLevel g = plugin.settings.gens.get(d).get(level(i, d));
        return g != null && g.blocks().getOrDefault(m, 0d) > 0;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onForm(BlockFormEvent e) {
        Block b = e.getBlock();
        if (!plugin.worlds.isIslandWorld(b.getWorld())) return;
        if (!FORMED.contains(e.getNewState().getType())) return;
        Island i = plugin.islands.at(b);
        if (i == null) return;
        Dim d = plugin.worlds.dim(b.getWorld());
        Weighted<Material> table = tables.getOrDefault(d, Map.of()).get(level(i, d));
        plugin.placed.unmark(b);
        if (table == null) return;
        Material m = table.pick();
        if (m != null) e.getNewState().setType(m);
    }

    /** Lets players place water on the Nether mini-island so generators work there too. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onNetherWater(PlayerBucketEmptyEvent e) {
        if (e.getBucket() != Material.WATER_BUCKET) return;
        Block target = e.getBlock();
        if (plugin.worlds.dim(target.getWorld()) != Dim.NETHER) return;
        Player p = e.getPlayer();
        if (!plugin.islands.canBuild(p, target.getLocation())) return;
        e.setCancelled(true);
        if (!target.getType().isAir() && !target.isLiquid()) return;
        target.setType(Material.WATER, true);
        if (p.getGameMode() != org.bukkit.GameMode.CREATIVE) {
            EquipmentSlot hand = e.getHand();
            Bukkit.getScheduler().runTask(plugin, () -> p.getInventory().setItem(hand, new ItemStack(Material.BUCKET)));
        }
    }

    /** Called for every counted mining action: End Portal Frame roll on Nether generator blocks. */
    public void onMined(Player p, Block b, Island i) {
        if (plugin.worlds.dim(b.getWorld()) != Dim.NETHER) return;
        int minLevel = plugin.settings.generators.getInt("end-portal-frames.min-level", 4);
        if (i.genNether < minLevel) return;
        Settings.GenLevel g = plugin.settings.gens.get(Dim.NETHER).get(i.genNether);
        if (g == null || !g.blocks().containsKey(b.getType())) return;
        Profile prof = plugin.data.profile(p.getUniqueId());
        prof.netherPity++;
        int pity = plugin.settings.generators.getInt("end-portal-frames.pity-blocks", 10000);
        boolean drop = prof.netherPity >= pity || ThreadLocalRandom.current().nextDouble() < g.frameChance();
        if (drop) {
            prof.netherPity = 0;
            var left = p.getInventory().addItem(plugin.items.endFrame(1));
            left.values().forEach(it -> p.getWorld().dropItemNaturally(p.getLocation(), it));
            Text.msg(p, "<light_purple>You found an <white>End Portal Frame</white>!");
            p.playSound(p.getLocation(), Sound.BLOCK_END_PORTAL_FRAME_FILL, 1f, 1f);
        }
        plugin.data.dirty(prof);
    }
}
