package dev.skycore.crops;

import dev.skycore.SkyCore;
import dev.skycore.config.Settings;
import dev.skycore.data.Island;
import dev.skycore.data.Profile;
import dev.skycore.items.SpecialItems;
import dev.skycore.skills.Skill;
import dev.skycore.util.Pos;
import dev.skycore.util.Text;
import dev.skycore.world.Dim;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Ageable;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.inventory.ItemStack;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Special crops: permanent blocks. Harvest by hand when ripe -> drops straight to the inventory and the
 * crop resets to stage 0. It regrows on SkyCore's own scheduler (no random ticks, never replant).
 */
public final class CropService implements Listener {
    private static final BlockFace[] SIDES = {BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};
    private final SkyCore plugin;

    public CropService(SkyCore plugin) {
        this.plugin = plugin;
    }

    private Settings.CropType type(Island.SpecialCrop c) {
        return plugin.settings.crops.get(c.type);
    }

    public long regrowMs(Island i, UUID harvester) {
        var g = plugin.settings.cropGrowth.get(i.growthLevel);
        double sec = g == null ? 120 : g.amount();
        Profile p = harvester == null ? null : plugin.data.profile(harvester);
        if (p != null) sec *= 1 - plugin.pets.regrowBonus(p);
        return (long) (sec * 1000);
    }

    /** The block of a crop that is "the crop" (for sugar cane: the base block). */
    private Island.SpecialCrop cropAt(Island i, Block b) {
        return i.crops.get(Pos.key(b));
    }

    public boolean isCrop(Block b) {
        Island i = plugin.islands.at(b);
        if (i == null) return false;
        if (i.crops.containsKey(Pos.key(b))) return true;
        Island.SpecialCrop below = i.crops.get(Pos.key(b.getRelative(BlockFace.DOWN)));
        return below != null && "sugar_cane".equals(below.type) && b.getType() == Material.SUGAR_CANE;
    }

    // ------------------------------------------------------------------ placement
    public boolean place(Player p, Island i, Block b, ItemStack item) {
        String id = SpecialItems.data(item);
        Settings.CropType c = plugin.settings.crops.get(id);
        if (c == null) return false;
        if (c.netherOnly() && plugin.worlds.dim(b.getWorld()) != Dim.NETHER) {
            Text.msg(p, "<red>Special " + c.name() + " only grows on your Nether island.");
            return false;
        }
        if (b.getType() != c.block()) return false;
        Island.SpecialCrop sc = new Island.SpecialCrop();
        sc.type = id;
        sc.planter = p.getUniqueId();
        sc.due = System.currentTimeMillis() + regrowMs(i, p.getUniqueId());
        i.crops.put(Pos.key(b), sc);
        plugin.data.dirty(i);
        return true;
    }

    // ------------------------------------------------------------------ growth scheduler
    public void tick() {
        long now = System.currentTimeMillis();
        for (Island i : plugin.islands.active()) {
            Iterator<Map.Entry<String, Island.SpecialCrop>> it = i.crops.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, Island.SpecialCrop> e = it.next();
                Pos pos = Pos.parse(e.getKey());
                if (!pos.isLoaded()) continue;
                Island.SpecialCrop c = e.getValue();
                Settings.CropType t = type(c);
                Block b = pos.block();
                if (t == null || b.getType() != t.block()) {
                    // destroyed by physics (support block removed): give the seed back
                    it.remove();
                    plugin.data.dirty(i);
                    if (t != null) plugin.rewards.giveOrBox(c.planter, plugin.items.seedPackSingle(c.planter, c.type));
                    continue;
                }
                if (c.due > 0 && now >= c.due) {
                    mature(b, t);
                    c.due = 0;
                    plugin.data.dirty(i);
                }
            }
        }
    }

    private void mature(Block b, Settings.CropType t) {
        if (t.block() == Material.SUGAR_CANE) {
            Block up = b.getRelative(BlockFace.UP);
            if (up.getType().isAir()) up.setType(Material.SUGAR_CANE, false);
            return;
        }
        if (b.getBlockData() instanceof Ageable a) {
            a.setAge(a.getMaximumAge());
            b.setBlockData(a, false);
        }
    }

    private boolean isRipe(Block b, Settings.CropType t) {
        if (t.block() == Material.SUGAR_CANE) return b.getRelative(BlockFace.UP).getType() == Material.SUGAR_CANE;
        return b.getBlockData() instanceof Ageable a && a.getAge() >= a.getMaximumAge();
    }

    private void reset(Block b, Settings.CropType t) {
        if (t.block() == Material.SUGAR_CANE) {
            b.getRelative(BlockFace.UP).setType(Material.AIR, false);
            return;
        }
        if (b.getBlockData() instanceof Ageable a) {
            a.setAge(0);
            b.setBlockData(a, false);
        }
    }

    // ------------------------------------------------------------------ harvesting
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        if (!plugin.worlds.isIslandWorld(b.getWorld())) return;
        Island i = plugin.islands.at(b);
        if (i == null) return;
        Island.SpecialCrop c = cropAt(i, b);
        Block base = b;
        if (c == null && b.getType() == Material.SUGAR_CANE) {
            base = b.getRelative(BlockFace.DOWN);
            c = cropAt(i, base);
            if (c != null && !"sugar_cane".equals(c.type)) c = null;
        }
        if (c == null) {
            // breaking the block under a special crop would destroy it
            if (cropAt(i, b.getRelative(BlockFace.UP)) != null) {
                e.setCancelled(true);
                Text.bar(e.getPlayer(), "<red>Pick up the special crop above first (sneak + break).");
            }
            return;
        }
        e.setCancelled(true);
        Player p = e.getPlayer();
        Settings.CropType t = type(c);
        if (t == null) return;
        if (isRipe(base, t)) {
            harvest(p, i, base, c, t);
        } else if (p.isSneaking() && base.equals(b)) {
            i.crops.remove(Pos.key(base));
            plugin.data.dirty(i);
            base.setType(Material.AIR);
            plugin.rewards.giveOrBox(c.planter, plugin.items.seedPackSingle(c.planter, c.type));
            Text.msg(p, "Special crop picked up.");
        } else {
            long left = Math.max(0, (c.due - System.currentTimeMillis()) / 1000);
            Text.bar(p, "<yellow>Not ripe yet <gray>(" + Text.duration(left) + "). <dark_gray>Sneak + break to pick it up.");
        }
    }

    private void harvest(Player p, Island i, Block base, Island.SpecialCrop c, Settings.CropType t) {
        int amount = t.amount();
        Profile prof = plugin.data.profile(p.getUniqueId());
        boolean counted = p.getGameMode() == GameMode.SURVIVAL
                && plugin.skills.action(p, Skill.FARMING, plugin.settings.skills.getDouble("farming.special-xp", 6));
        if (counted) {
            if (plugin.skills.rollDouble(prof, Skill.FARMING)) amount *= 2;
            plugin.islandMissions.progress(i, "harvest", 1);
        }
        var left = p.getInventory().addItem(new ItemStack(t.drop(), amount));
        left.values().forEach(it -> p.getWorld().dropItemNaturally(base.getLocation().add(0.5, 1, 0.5), it));
        reset(base, t);
        c.due = System.currentTimeMillis() + regrowMs(i, p.getUniqueId());
        plugin.data.dirty(i);
    }

    // ------------------------------------------------------------------ protection of crops
    @EventHandler(ignoreCancelled = true)
    public void onGrow(BlockGrowEvent e) {
        Block b = e.getBlock();
        if (!plugin.worlds.isIslandWorld(b.getWorld())) return;
        Island i = plugin.islands.at(b);
        if (i == null) return;
        if (i.crops.containsKey(Pos.key(b))) {
            e.setCancelled(true);
            return;
        }
        Material nt = e.getNewState().getType();
        if (nt == Material.SUGAR_CANE && i.crops.containsKey(Pos.key(b.getRelative(BlockFace.DOWN)))) {
            e.setCancelled(true);
            return;
        }
        if (nt == Material.PUMPKIN || nt == Material.MELON) {
            for (BlockFace f : SIDES) if (i.crops.containsKey(Pos.key(b.getRelative(f)))) {
                e.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onFertilize(BlockFertilizeEvent e) {
        if (isCrop(e.getBlock())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onFlow(BlockFromToEvent e) {
        if (isCrop(e.getToBlock())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onFade(BlockFadeEvent e) {
        if (e.getBlock().getType() == Material.FARMLAND && isCrop(e.getBlock().getRelative(BlockFace.UP))) e.setCancelled(true);
    }
}
