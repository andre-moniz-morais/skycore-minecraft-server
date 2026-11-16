package dev.skycore.skills;

import dev.skycore.SkyCore;
import dev.skycore.data.Island;
import dev.skycore.data.Profile;
import dev.skycore.world.Dim;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerHarvestBlockEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.*;

/**
 * Skill actions: mining generator/natural blocks, harvesting crops by hand, fishing, killing mobs.
 * Also the magnet (drops straight to the inventory, no item entities), double drops and level-10 perks.
 */
public final class SkillListener implements Listener {
    private static final Map<Material, Material> CROP_SEED = Map.of(
            Material.WHEAT, Material.WHEAT_SEEDS, Material.CARROTS, Material.CARROT, Material.POTATOES, Material.POTATO,
            Material.BEETROOTS, Material.BEETROOT_SEEDS, Material.NETHER_WART, Material.NETHER_WART, Material.COCOA, Material.COCOA_BEANS);
    private static final Set<Material> FRUIT = EnumSet.of(Material.PUMPKIN, Material.MELON, Material.SUGAR_CANE, Material.CACTUS, Material.BAMBOO);
    /** Plants that pop every block above when one is broken; SkyCore breaks those itself so the drops are not lost. */
    private static final Set<Material> COLUMN = EnumSet.of(Material.SUGAR_CANE, Material.CACTUS, Material.BAMBOO);
    /** Other plant blocks whose drops go straight into the inventory (no skill XP). */
    private static final Set<Material> PLANTS = EnumSet.of(Material.PUMPKIN_STEM, Material.MELON_STEM,
            Material.ATTACHED_PUMPKIN_STEM, Material.ATTACHED_MELON_STEM, Material.BAMBOO_SAPLING, Material.SWEET_BERRY_BUSH);
    private static final Set<Material> TREASURE = EnumSet.of(Material.BOW, Material.ENCHANTED_BOOK, Material.FISHING_ROD,
            Material.NAME_TAG, Material.NAUTILUS_SHELL, Material.SADDLE);
    private static final Set<CreatureSpawnEvent.SpawnReason> NO_XP_REASONS = EnumSet.of(
            CreatureSpawnEvent.SpawnReason.BUILD_IRONGOLEM, CreatureSpawnEvent.SpawnReason.BUILD_SNOWMAN,
            CreatureSpawnEvent.SpawnReason.BUILD_WITHER, CreatureSpawnEvent.SpawnReason.SPAWNER,
            CreatureSpawnEvent.SpawnReason.SPAWNER_EGG, CreatureSpawnEvent.SpawnReason.CUSTOM,
            CreatureSpawnEvent.SpawnReason.COMMAND, CreatureSpawnEvent.SpawnReason.SLIME_SPLIT,
            CreatureSpawnEvent.SpawnReason.DUPLICATION);

    private final SkyCore plugin;
    /** Which skill a block break counted for, consumed by the drop event in the same tick. */
    private final Map<String, Skill> counted = new HashMap<>();

    public SkillListener(SkyCore plugin) {
        this.plugin = plugin;
    }

    private void remember(String key, Skill s) {
        if (counted.isEmpty()) Bukkit.getScheduler().runTask(plugin, counted::clear);
        counted.put(key, s);
    }

    private static boolean isMatureCrop(BlockState s) {
        BlockData d = s.getBlockData();
        return CROP_SEED.containsKey(s.getType()) && d instanceof Ageable a && a.getAge() >= a.getMaximumAge();
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        Block b = e.getBlock();
        if (p.getGameMode() != GameMode.SURVIVAL || !plugin.worlds.isIslandWorld(b.getWorld())) return;
        Island island = plugin.islands.at(b);
        if (island == null || !island.isMember(p.getUniqueId())) return;
        Material m = b.getType();
        Material above = b.getRelative(BlockFace.UP).getType();
        if (COLUMN.contains(above) && (above == m || !COLUMN.contains(m))) breakColumnAbove(p, island, b, above);
        String key = b.getWorld().getName() + ";" + b.getX() + ";" + b.getY() + ";" + b.getZ();
        Double xp = plugin.settings.miningXp.get(m);
        if (xp != null && !plugin.placed.isPlaced(b)) {
            if (plugin.skills.action(p, Skill.MINING, xp)) {
                remember(key, Skill.MINING);
                plugin.islandMissions.progress(island, "gen_blocks", 1);
                plugin.islandMissions.progress(island, "break:" + m.name(), 1);
                if (plugin.worlds.dim(b.getWorld()) == Dim.END) plugin.islandMissions.progress(island, "end_gen_blocks", 1);
                plugin.generators.onMined(p, b, island);
            }
            return;
        }
        boolean harvest = isMatureCrop(b.getState()) || (FRUIT.contains(m) && !plugin.placed.isPlaced(b)) || hasBerries(b.getBlockData());
        if (harvest) {
            if (plugin.skills.action(p, Skill.FARMING, plugin.settings.skills.getDouble("farming.xp", 4))) {
                remember(key, Skill.FARMING);
                plugin.islandMissions.progress(island, "harvest", 1);
            }
        }
    }

    /**
     * Breaking sugar cane / cactus / bamboo (or the block it stands on) would make every block above drop on the floor through physics.
     * Break them here, top first and without physics, and hand each block's drops to the player. Naturally grown
     * blocks count as harvests (XP, double drops); player-placed ones do not, exactly as if broken one by one.
     */
    private void breakColumnAbove(Player p, Island island, Block b, Material m) {
        List<Block> above = new ArrayList<>();
        for (Block up = b.getRelative(BlockFace.UP); up.getType() == m && above.size() < 64; up = up.getRelative(BlockFace.UP)) above.add(up);
        if (above.isEmpty()) return;
        Profile prof = plugin.data.profile(p.getUniqueId());
        ItemStack tool = p.getInventory().getItemInMainHand();
        double xp = plugin.settings.skills.getDouble("farming.xp", 4);
        for (Block up : above.reversed()) {
            List<ItemStack> drops = new ArrayList<>(up.getDrops(tool, p));
            if (plugin.placed.isPlaced(up)) {
                double worth = plugin.islands.blockWorth(m);
                if (worth > 0) plugin.islands.addBlockValue(island, -worth);
            } else if (prof != null && plugin.skills.action(p, Skill.FARMING, xp)) {
                plugin.islandMissions.progress(island, "harvest", 1);
                if (plugin.skills.rollDouble(prof, Skill.FARMING)) for (ItemStack d : drops) d.setAmount(Math.min(d.getMaxStackSize(), d.getAmount() * 2));
            }
            plugin.placed.unmark(up);
            up.setType(Material.AIR, false);
            for (ItemStack d : drops)
                p.getInventory().addItem(d).values().forEach(l -> up.getWorld().dropItemNaturally(up.getLocation().add(0.5, 0.5, 0.5), l));
        }
    }

    private static boolean isPlant(Material m) {
        return PLANTS.contains(m) || Tag.SAPLINGS.isTagged(m) || Tag.LEAVES.isTagged(m) || Tag.LOGS.isTagged(m);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrops(BlockDropItemEvent e) {
        Player p = e.getPlayer();
        Block b = e.getBlock();
        String key = b.getWorld().getName() + ";" + b.getX() + ";" + b.getY() + ";" + b.getZ();
        Skill skill = counted.remove(key);
        if (!plugin.worlds.isIslandWorld(b.getWorld())) return;
        BlockState state = e.getBlockState();
        Material m = state.getType();
        boolean magnetBlock = plugin.settings.miningXp.containsKey(m) || CROP_SEED.containsKey(m) || FRUIT.contains(m) || isPlant(m);
        if (!magnetBlock) return;
        Profile prof = plugin.data.profile(p.getUniqueId());
        if (prof == null) return;

        // double drops
        if (skill != null && plugin.skills.rollDouble(prof, skill)) {
            for (Item it : e.getItems()) {
                ItemStack s = it.getItemStack();
                s.setAmount(Math.min(s.getMaxStackSize(), s.getAmount() * 2));
                it.setItemStack(s);
            }
        }
        // auto replant (Farming 10) for normal mature crops
        if (skill == Skill.FARMING && CROP_SEED.containsKey(m) && plugin.skills.hasUtilityPerk(prof, Skill.FARMING)) {
            Material seed = CROP_SEED.get(m);
            for (Item it : e.getItems()) {
                ItemStack s = it.getItemStack();
                if (s.getType() == seed) {
                    s.setAmount(s.getAmount() - 1);
                    it.setItemStack(s);
                    BlockData data = state.getBlockData().clone();
                    if (data instanceof Ageable a) a.setAge(0);
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (b.getType().isAir()) b.setBlockData(data, true);
                    });
                    break;
                }
            }
        }
        // magnet: straight into the inventory, never spawn item entities unless it is full
        Iterator<Item> it = e.getItems().iterator();
        while (it.hasNext()) {
            Item item = it.next();
            ItemStack s = item.getItemStack();
            if (s.getAmount() <= 0) {
                it.remove();
                continue;
            }
            Map<Integer, ItemStack> left = p.getInventory().addItem(s);
            if (left.isEmpty()) it.remove();
            else item.setItemStack(left.values().iterator().next());
        }
    }

    /** Sweet berry bushes carry berries from age 2 (vanilla drops 1-2 at age 2, 2-3 at age 3). */
    private static boolean hasBerries(BlockData d) {
        return d.getMaterial() == Material.SWEET_BERRY_BUSH && d instanceof Ageable a && a.getAge() >= 2;
    }

    /** Right-click harvest of sweet berries: counts as a Farming harvest and the berries go straight to the inventory. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHarvest(PlayerHarvestBlockEvent e) {
        Player p = e.getPlayer();
        Block b = e.getHarvestedBlock();
        if (b.getType() != Material.SWEET_BERRY_BUSH || !plugin.worlds.isIslandWorld(b.getWorld())) return;
        Island island = plugin.islands.at(b);
        if (island == null || !island.isMember(p.getUniqueId())) return;
        Profile prof = plugin.data.profile(p.getUniqueId());
        if (prof == null) return;
        List<ItemStack> items = e.getItemsHarvested();
        if (p.getGameMode() == GameMode.SURVIVAL && plugin.skills.action(p, Skill.FARMING, plugin.settings.skills.getDouble("farming.xp", 4))) {
            plugin.islandMissions.progress(island, "harvest", 1);
            if (plugin.skills.rollDouble(prof, Skill.FARMING))
                for (ItemStack s : items) s.setAmount(Math.min(s.getMaxStackSize(), s.getAmount() * 2));
        }
        magnet(p, items); // whatever does not fit stays in the list and vanilla drops it
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFish(PlayerFishEvent e) {
        Player p = e.getPlayer();
        if (!plugin.worlds.isIslandWorld(p.getWorld())) return;
        Profile prof = plugin.data.profile(p.getUniqueId());
        if (prof == null) return;
        if (e.getState() == PlayerFishEvent.State.FISHING) {
            if (plugin.skills.hasUtilityPerk(prof, Skill.FISHING)) {
                double f = 1 - plugin.settings.skills.getDouble("perks.fishing-wait-reduction", 0.2);
                FishHook hook = e.getHook();
                hook.setMinWaitTime((int) (hook.getMinWaitTime() * f));
                hook.setMaxWaitTime((int) (hook.getMaxWaitTime() * f));
            }
            return;
        }
        if (e.getState() != PlayerFishEvent.State.CAUGHT_FISH || !(e.getCaught() instanceof Item caught)) return;
        boolean treasure = TREASURE.contains(caught.getItemStack().getType());
        double xp = plugin.settings.skills.getDouble(treasure ? "fishing.treasure-xp" : "fishing.xp", treasure ? 60 : 15);
        if (plugin.skills.action(p, Skill.FISHING, xp)) {
            Island i = plugin.islands.at(p.getLocation());
            if (i != null) plugin.islandMissions.progress(i, "fish", 1);
            if (plugin.skills.rollDouble(prof, Skill.FISHING)) p.getInventory().addItem(caught.getItemStack().clone());
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onKill(EntityDeathEvent e) {
        LivingEntity ent = e.getEntity();
        if (ent instanceof Player || ent instanceof ArmorStand) return;
        if (!plugin.worlds.isIslandWorld(ent.getWorld())) return;
        if (ent.getPersistentDataContainer().has(dev.skycore.util.Keys.MOB, PersistentDataType.STRING)) return; // spawner mobs
        Player killer = ent.getKiller();
        if (killer == null || killer.getGameMode() != GameMode.SURVIVAL) return;
        if (NO_XP_REASONS.contains(ent.getEntitySpawnReason())) return;
        double xp = combatXp(ent);
        if (!plugin.skills.action(killer, Skill.COMBAT, xp)) return;
        Profile prof = plugin.data.profile(killer.getUniqueId());
        Island i = plugin.islands.at(ent.getLocation());
        if (i != null) plugin.islandMissions.progress(i, "kills", 1);
        if (plugin.skills.rollDouble(prof, Skill.COMBAT)) {
            List<ItemStack> extra = new ArrayList<>();
            for (ItemStack s : e.getDrops()) extra.add(s.clone());
            e.getDrops().addAll(extra);
        }
        if (plugin.skills.hasUtilityPerk(prof, Skill.COMBAT)) magnet(killer, e.getDrops());
    }

    public double combatXp(LivingEntity ent) {
        var attr = ent.getAttribute(Attribute.MAX_HEALTH);
        double hp = attr == null ? 20 : attr.getValue();
        var c = plugin.settings.skills;
        return Math.max(c.getDouble("combat.min-xp", 5), Math.min(c.getDouble("combat.max-xp", 25), hp * c.getDouble("combat.xp-per-health", 1.25)));
    }

    public static void magnet(Player p, List<ItemStack> drops) {
        Iterator<ItemStack> it = drops.iterator();
        List<ItemStack> leftovers = new ArrayList<>();
        while (it.hasNext()) {
            ItemStack s = it.next();
            it.remove();
            leftovers.addAll(p.getInventory().addItem(s).values());
        }
        drops.addAll(leftovers);
    }

    /** Mining 10 perk: Haste I while on an island you are a member of. Runs every 5 seconds. */
    public void tickHaste() {
        int amp = plugin.settings.skills.getInt("perks.mining-haste-amplifier", 0);
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!plugin.worlds.isIslandWorld(p.getWorld())) continue;
            Profile prof = plugin.data.profile(p.getUniqueId());
            if (prof == null || !plugin.skills.hasUtilityPerk(prof, Skill.MINING)) continue;
            Island i = plugin.islands.at(p.getLocation());
            if (i == null || !i.isMember(p.getUniqueId())) continue;
            p.addPotionEffect(new PotionEffect(PotionEffectType.HASTE, 140, amp, true, false, true));
        }
    }
}
