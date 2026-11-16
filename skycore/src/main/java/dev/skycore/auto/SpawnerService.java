package dev.skycore.auto;

import com.destroystokyo.paper.event.entity.PreSpawnerSpawnEvent;
import dev.skycore.SkyCore;
import dev.skycore.config.Settings;
import dev.skycore.data.Island;
import dev.skycore.data.Profile;
import dev.skycore.gui.Menu;
import dev.skycore.items.SpecialItems;
import dev.skycore.skills.Skill;
import dev.skycore.skills.SkillListener;
import dev.skycore.util.Keys;
import dev.skycore.util.Pos;
import dev.skycore.util.Text;
import dev.skycore.world.Dim;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.event.world.EntitiesUnloadEvent;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Stacked spawners: one block, one entity. Every cycle the block spawns a single entity that represents
 * stack x rate mobs. A player kill yields loot for all of them plus ONE Combat action; otherwise the
 * entity is harvested virtually (no XP, no Roses) at the next cycle. Dollar yield is identical either way.
 */
public final class SpawnerService implements Listener {
    private final SkyCore plugin;

    public SpawnerService(SkyCore plugin) {
        this.plugin = plugin;
    }

    private Settings.SpawnerType type(Island.SpawnerData s) {
        return plugin.settings.spawnerTypes.get(s.type);
    }

    public int maxStack(Island i, Dim d) {
        int base = plugin.settings.islandLevel(i.level).spawnerStack();
        return (int) Math.floor(base * plugin.settings.spawnerDimFactor.getOrDefault(d, 1.0));
    }

    public long totalUnits(Island i) {
        long t = 0;
        for (Island.SpawnerData s : i.spawners.values()) t += s.stack;
        return t;
    }

    public int maxStackOnIsland(Island i, String typeOrAny) {
        int max = 0;
        for (Island.SpawnerData s : i.spawners.values())
            if (typeOrAny.equals("any") || s.type.equals(typeOrAny)) max = Math.max(max, s.stack);
        return max;
    }

    private String limitError(Island i, Dim d, long newStack, long addedUnits) {
        int max = maxStack(i, d);
        if (max <= 0) return "Spawners unlock at island level 2.";
        if (newStack > max) return "Max stack per spawner here is " + max + " (island level " + i.level + ", " + d.display + ").";
        long total = totalUnits(i) + addedUnits;
        int cap = plugin.settings.islandLevel(i.level).spawnersTotal();
        if (total > cap) return "Island spawner unit limit is " + cap + ".";
        return null;
    }

    // ------------------------------------------------------------------ placement
    public boolean place(Player p, Island i, Block b, ItemStack item) {
        String typeId = SpecialItems.data(item);
        Settings.SpawnerType t = plugin.settings.spawnerTypes.get(typeId);
        if (t == null) return false;
        long units = Math.max(1, SpecialItems.amount(item));
        Dim d = plugin.worlds.dim(b.getWorld());
        if (t.dimension() != d) {
            Text.msg(p, "<red>" + t.name() + " spawners can only be placed in the " + t.dimension().display + ".");
            return false;
        }
        Profile prof = plugin.data.profile(p.getUniqueId());
        if (!prof.unlockedSpawners.contains(typeId)) {
            Text.msg(p, "<red>Unlock the " + t.name() + " spawner type first (/shop > Spawners).");
            return false;
        }
        String err = limitError(i, d, units, units);
        if (err != null) {
            Text.msg(p, "<red>" + err);
            return false;
        }
        Island.SpawnerData s = new Island.SpawnerData();
        s.type = typeId;
        s.stack = (int) units;
        s.last = System.currentTimeMillis();
        s.placer = p.getUniqueId();
        i.spawners.put(Pos.key(b), s);
        plugin.data.dirty(i);
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (b.getState() instanceof CreatureSpawner cs) {
                cs.setSpawnedType(t.entity());
                cs.update(true, false);
            }
        });
        Text.msg(p, "<green>" + t.name() + " spawner placed with <white>" + units + "</white> units.");
        plugin.islandMissions.check(i);
        return true;
    }

    public void interact(Player p, Island i, Block b, Island.SpawnerData s) {
        ItemStack hand = p.getInventory().getItemInMainHand();
        Settings.SpawnerType t = type(s);
        if (t == null) return;
        if (SpecialItems.is(hand, SpecialItems.SPAWNER)) {
            if (!s.type.equals(SpecialItems.data(hand))) {
                Text.msg(p, "<red>You can only stack spawners of the same type.");
                return;
            }
            long add = Math.max(1, SpecialItems.amount(hand));
            String err = limitError(i, plugin.worlds.dim(b.getWorld()), s.stack + add, add);
            if (err != null) {
                Text.msg(p, "<red>" + err);
                return;
            }
            hand.setAmount(hand.getAmount() - 1);
            s.stack += (int) add;
            plugin.data.dirty(i);
            Text.msg(p, "<green>Stack is now <white>" + s.stack + "</white> " + t.name() + " units.");
            plugin.islandMissions.check(i);
            plugin.path.check(p);
            return;
        }
        if (p.isSneaking() && hand.isEmpty()) {
            pickup(p, i, b, s);
            return;
        }
        open(p, i, b, s);
    }

    public void pickup(Player p, Island i, Block b, Island.SpawnerData s) {
        collectStorage(p, i, s);
        if (!s.storage.isEmpty()) {
            Text.msg(p, "<yellow>Make room to collect the spawner storage first.");
            return;
        }
        removeEntity(s);
        i.spawners.remove(Pos.key(b));
        plugin.data.dirty(i);
        b.setType(Material.AIR);
        var left = p.getInventory().addItem(plugin.items.spawner(s.type, s.stack));
        left.values().forEach(it -> p.getWorld().dropItemNaturally(p.getLocation(), it));
        Text.msg(p, "Picked up " + s.stack + " " + type(s).name() + " spawner units.");
    }

    private void removeEntity(Island.SpawnerData s) {
        if (s.entity == null) return;
        Entity e = Bukkit.getEntity(UUID.fromString(s.entity));
        if (e != null) e.remove();
        s.entity = null;
        s.entityCount = 0;
    }

    // ------------------------------------------------------------------ cycles
    public void tick() {
        long now = System.currentTimeMillis();
        for (Island i : plugin.islands.active()) {
            for (Map.Entry<String, Island.SpawnerData> en : i.spawners.entrySet()) cycle(i, en.getKey(), en.getValue(), now);
        }
    }

    /** Runs all due cycles of one spawner (also used by /sc selftest). */
    public void cycle(Island i, String key, Island.SpawnerData s, long now) {
        long cycleMs = plugin.settings.spawners.getLong("cycle-seconds", 20) * 1000L;
        long cap = plugin.settings.config.getLong("automation.offline-cap-hours", 12) * 3_600_000L;
        double rate = plugin.settings.spawners.getDouble("mobs-per-unit-per-cycle", 1 / 3.0);
        Settings.SpawnerType t = type(s);
        if (t == null) return;
        if (s.last < now - cap) s.last = now - cap;
        long cycles = (now - s.last) / cycleMs;
        if (cycles <= 0) return;
        s.last += cycles * cycleMs;
        Pos pos = Pos.parse(key);
        // the previous entity was not killed by a player: harvest it virtually
        if (s.entity != null) {
            Entity old = Bukkit.getEntity(UUID.fromString(s.entity));
            long count = s.entityCount;
            if (old != null) old.remove();
            s.entity = null;
            s.entityCount = 0;
            harvest(i, pos, s, count);
        }
        double perCycle = s.stack * rate;
        double total = s.frac + perCycle * cycles;
        long mobs = (long) Math.floor(total);
        s.frac = total - mobs;
        long entityMobs = 0;
        if (pos.isLoaded() && mobs > 0) {
            entityMobs = Math.min(mobs, Math.max(1, (long) Math.ceil(perCycle)));
            if (!spawnEntity(i, pos, s, t, entityMobs)) entityMobs = 0;
        }
        harvest(i, pos, s, mobs - entityMobs);
        plugin.data.dirty(i);
    }

    private boolean spawnEntity(Island i, Pos pos, Island.SpawnerData s, Settings.SpawnerType t, long count) {
        Block b = pos.block();
        if (b == null || b.getType() != Material.SPAWNER) return false;
        Location loc = b.getLocation().add(0.5, 1, 0.5);
        Class<? extends Entity> cls = t.entity().getEntityClass();
        if (cls == null) return false;
        String tag = i.id + "|" + pos.key() + "|" + count;
        Entity ent = b.getWorld().spawn(loc, cls, e -> {
            e.getPersistentDataContainer().set(Keys.MOB, PersistentDataType.STRING, tag);
            e.setPersistent(false);
            e.setSilent(true);
            e.customName(Text.mm("<yellow>x" + count + " <white>" + t.name()));
            e.setCustomNameVisible(true);
            if (e instanceof LivingEntity le) {
                le.setAI(false);
                le.setRemoveWhenFarAway(false);
                le.setCanPickupItems(false);
            }
            if (e instanceof Slime sl) sl.setSize(1);
            if (e instanceof Ageable a) a.setAdult();
            if (e instanceof Zombie z) z.setShouldBurnInDay(false);
            if (e instanceof AbstractSkeleton sk) sk.setShouldBurnInDay(false);
        });
        s.entity = ent.getUniqueId().toString();
        s.entityCount = count;
        return true;
    }

    /** Expected loot for n mobs; fractional parts are rolled so averages stay exact. */
    public Map<Material, Long> loot(Settings.SpawnerType t, long mobs) {
        Map<Material, Long> out = new EnumMap<>(Material.class);
        if (mobs <= 0) return out;
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (Settings.Loot l : t.loot()) {
            double exp = mobs * l.amount() * l.chance();
            long whole = (long) Math.floor(exp);
            if (r.nextDouble() < exp - whole) whole++;
            if (whole > 0) out.merge(l.item(), whole, Long::sum);
        }
        return out;
    }

    private void harvest(Island i, Pos pos, Island.SpawnerData s, long mobs) {
        Settings.SpawnerType t = type(s);
        if (t == null || mobs <= 0) return;
        long capacity = plugin.settings.spawners.getLong("storage-stacks", 54) * 64;
        for (Map.Entry<Material, Long> e : loot(t, mobs).entrySet()) {
            if (plugin.sellChests.deliver(i, pos, e.getKey(), e.getValue(), "spawner")) continue;
            long stored = s.storage.values().stream().mapToLong(Long::longValue).sum();
            long room = Math.max(0, capacity - stored);
            long add = Math.min(room, e.getValue());
            if (add > 0) s.storage.merge(e.getKey().name(), add, Long::sum);
        }
    }

    public void collectStorage(Player p, Island i, Island.SpawnerData s) {
        Iterator<Map.Entry<String, Long>> it = s.storage.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Long> e = it.next();
            Material m = Material.matchMaterial(e.getKey());
            if (m == null) {
                it.remove();
                continue;
            }
            long left = e.getValue();
            while (left > 0) {
                int n = (int) Math.min(m.getMaxStackSize(), left);
                int notAdded = p.getInventory().addItem(new ItemStack(m, n)).values().stream().mapToInt(ItemStack::getAmount).sum();
                left -= (n - notAdded);
                if (notAdded > 0) break;
            }
            if (left <= 0) it.remove();
            else {
                e.setValue(left);
                break;
            }
        }
        plugin.data.dirty(i);
    }

    // ------------------------------------------------------------------ menu
    private void open(Player p, Island i, Block b, Island.SpawnerData s) {
        Settings.SpawnerType t = type(s);
        Dim d = plugin.worlds.dim(b.getWorld());
        double rate = plugin.settings.spawners.getDouble("mobs-per-unit-per-cycle", 1 / 3.0);
        long cycle = plugin.settings.spawners.getLong("cycle-seconds", 20);
        double mobsPerHour = s.stack * rate * 3600.0 / cycle;
        Menu m = new Menu(3, "<dark_gray>" + t.name() + " Spawner");
        boolean linked = plugin.sellChests.linked(i, Pos.of(b)) != null;
        m.set(4, Menu.icon(Material.SPAWNER, "<yellow>" + t.name() + " Spawner", List.of(
                "Stack: <white>" + s.stack + "<gray>/" + maxStack(i, d),
                "Island units: <white>" + totalUnits(i) + "<gray>/" + plugin.settings.islandLevel(i.level).spawnersTotal(),
                "Mobs per hour: <white>" + Text.num(Math.round(mobsPerHour)),
                "Delivery: " + (linked ? "<green>linked sell chest" : "<yellow>internal storage"),
                "",
                "Kill the spawned mob yourself for Combat XP.",
                "Right-click with units of the same type to stack.",
                "Sneak + right-click with an empty hand to pick up.")));
        List<String> lore = new ArrayList<>();
        long total = 0;
        for (Map.Entry<String, Long> e : s.storage.entrySet()) {
            lore.add("<white>" + Text.num(e.getValue()) + "x <gray>" + Text.pretty(e.getKey()));
            total += e.getValue();
        }
        if (lore.isEmpty()) lore.add("Empty");
        lore.add("");
        lore.add("<green>Click to collect");
        m.set(13, Menu.icon(Material.CHEST, "<yellow>Storage <gray>(" + Text.num(total) + ")", lore), e -> {
            collectStorage(p, i, s);
            open(p, i, b, s);
        });
        m.fill(Material.GRAY_STAINED_GLASS_PANE);
        m.open(p);
    }

    // ------------------------------------------------------------------ entity events
    private static String tag(Entity e) {
        return e.getPersistentDataContainer().get(Keys.MOB, PersistentDataType.STRING);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(EntityDeathEvent e) {
        String tag = tag(e.getEntity());
        if (tag == null) return;
        e.getDrops().clear();
        e.setDroppedExp(0);
        String[] parts = tag.split("\\|");
        Island i = plugin.islands.get(Integer.parseInt(parts[0]));
        long count = Long.parseLong(parts[2]);
        if (i == null) return;
        Island.SpawnerData s = i.spawners.get(parts[1]);
        if (s == null || !e.getEntity().getUniqueId().toString().equals(s.entity)) return;
        s.entity = null;
        s.entityCount = 0;
        plugin.data.dirty(i);
        Player killer = e.getEntity().getKiller();
        if (killer == null || killer.getGameMode() != GameMode.SURVIVAL || !i.isMember(killer.getUniqueId())) {
            harvest(i, Pos.parse(parts[1]), s, count);
            return;
        }
        Settings.SpawnerType t = type(s);
        List<ItemStack> drops = new ArrayList<>();
        for (Map.Entry<Material, Long> en : loot(t, count).entrySet()) {
            long left = en.getValue();
            while (left > 0) {
                int n = (int) Math.min(en.getKey().getMaxStackSize(), left);
                drops.add(new ItemStack(en.getKey(), n));
                left -= n;
            }
        }
        Profile prof = plugin.data.profile(killer.getUniqueId());
        if (plugin.skills.hasUtilityPerk(prof, Skill.COMBAT)) SkillListener.magnet(killer, drops);
        e.getDrops().addAll(drops);
        plugin.skills.action(killer, Skill.COMBAT, plugin.skillListener.combatXp(e.getEntity()));
        plugin.islandMissions.progress(i, "kills", 1);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (tag(e.getEntity()) == null) return;
        if (e instanceof EntityDamageByEntityEvent by) {
            Entity d = by.getDamager();
            if (d instanceof Player || (d instanceof Projectile pr && pr.getShooter() instanceof Player)) return;
        }
        if (e.getCause() == EntityDamageEvent.DamageCause.KILL) return;
        e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onCombust(EntityCombustEvent e) {
        if (tag(e.getEntity()) != null) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onTeleport(EntityTeleportEvent e) {
        if (tag(e.getEntity()) != null) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onSplit(SlimeSplitEvent e) {
        if (tag(e.getEntity()) != null) e.setCancelled(true);
    }

    @EventHandler
    public void onUnload(EntitiesUnloadEvent e) {
        for (Entity ent : e.getEntities()) {
            String tag = tag(ent);
            if (tag == null) continue;
            String[] parts = tag.split("\\|");
            Island i = plugin.islands.get(Integer.parseInt(parts[0]));
            if (i == null) continue;
            Island.SpawnerData s = i.spawners.get(parts[1]);
            if (s != null && ent.getUniqueId().toString().equals(s.entity)) {
                long count = s.entityCount;
                s.entity = null;
                s.entityCount = 0;
                harvest(i, Pos.parse(parts[1]), s, count);
                plugin.data.dirty(i);
            }
            ent.remove();
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onVanillaSpawn(SpawnerSpawnEvent e) {
        if (e.getSpawner() != null && plugin.worlds.isIslandWorld(e.getSpawner().getWorld())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPreSpawn(PreSpawnerSpawnEvent e) {
        if (plugin.worlds.isIslandWorld(e.getSpawnerLocation().getWorld())) e.setCancelled(true);
    }
}
