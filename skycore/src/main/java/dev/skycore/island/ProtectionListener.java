package dev.skycore.island;

import dev.skycore.SkyCore;
import dev.skycore.data.Island;
import dev.skycore.data.Profile;
import dev.skycore.items.SpecialItems;
import dev.skycore.util.Text;
import dev.skycore.world.Dim;
import io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.InventoryHolder;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Island protection: only owner + members build/interact inside an island. No PvP, no block damage. */
public final class ProtectionListener implements Listener {
    private final SkyCore plugin;
    private final Map<UUID, Long> lastWarn = new HashMap<>();

    public ProtectionListener(SkyCore plugin) {
        this.plugin = plugin;
    }

    private boolean islandWorld(org.bukkit.World w) {
        return plugin.worlds.isIslandWorld(w);
    }

    private void warn(Player p) {
        long now = System.currentTimeMillis();
        if (now - lastWarn.getOrDefault(p.getUniqueId(), 0L) > 2000) {
            lastWarn.put(p.getUniqueId(), now);
            Text.bar(p, "<red>You can only do that on an island you are a member of.");
        }
    }

    private boolean denied(Player p, Location l) {
        if (!islandWorld(l.getWorld())) return false;
        if (plugin.islands.canBuild(p, l)) return false;
        warn(p);
        return true;
    }

    // ------------------------------------------------------------------ blocks
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (denied(e.getPlayer(), e.getBlock().getLocation())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        Player p = e.getPlayer();
        Block b = e.getBlockPlaced();
        if (denied(p, b.getLocation())) {
            e.setCancelled(true);
            return;
        }
        if (!islandWorld(b.getWorld())) return;
        Island i = plugin.islands.at(b);
        if (i == null) return;
        if (b.getType() == Material.END_PORTAL_FRAME) {
            int need = plugin.settings.islands.getInt("unlocks.end-portal-level", 6);
            if (i.level < need || plugin.worlds.dim(b.getWorld()) != Dim.OVERWORLD) {
                e.setCancelled(true);
                Text.msg(p, "<red>End portals can only be built on the Overworld island from island level " + need + ".");
                return;
            }
        }
        if (b.getType() == Material.HOPPER && SpecialItems.type(e.getItemInHand()) == null) {
            int limit = plugin.settings.islandLevel(i.level).hoppers();
            if (i.hoppers >= limit) {
                e.setCancelled(true);
                Text.msg(p, "<red>Hopper limit reached (" + limit + "). Upgrade your island level for more.");
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlaceValue(BlockPlaceEvent e) {
        Block b = e.getBlockPlaced();
        if (!islandWorld(b.getWorld())) return;
        Island i = plugin.islands.at(b);
        if (i == null) return;
        if (b.getType() == Material.HOPPER && SpecialItems.type(e.getItemInHand()) == null) {
            i.hoppers++;
            plugin.data.dirty(i);
        }
        if (SpecialItems.type(e.getItemInHand()) != null) return;
        BlockState replaced = e.getBlockReplacedState();
        double delta = plugin.islands.blockWorth(b.getType());
        if (replaced != null && !replaced.getType().isAir()) delta -= plugin.islands.blockWorth(replaced.getType());
        plugin.islands.addBlockValue(i, delta);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreakValue(BlockBreakEvent e) {
        Block b = e.getBlock();
        if (!islandWorld(b.getWorld())) return;
        Island i = plugin.islands.at(b);
        if (i == null) return;
        Material m = b.getType();
        if (m == Material.HOPPER && !plugin.chunkHoppers.isChunkHopper(b)) {
            i.hoppers = Math.max(0, i.hoppers - 1);
            plugin.data.dirty(i);
        }
        // generator-produced blocks were never counted, so only subtract tracked placements
        if (plugin.placed.isTracked(m) && !plugin.placed.isPlaced(b)) return;
        double worth = plugin.islands.blockWorth(m);
        if (worth > 0) plugin.islands.addBlockValue(i, -worth);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent e) {
        if (denied(e.getPlayer(), e.getBlock().getLocation())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent e) {
        if (denied(e.getPlayer(), e.getBlock().getLocation())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent e) {
        Block b = e.getClickedBlock();
        if (b == null || !islandWorld(b.getWorld())) return;
        Player p = e.getPlayer();
        if (e.getAction() == org.bukkit.event.block.Action.PHYSICAL) {
            if (b.getType() == Material.FARMLAND) {
                e.setCancelled(true);
                return;
            }
            if (!plugin.islands.canBuild(p, b.getLocation())) e.setCancelled(true);
            return;
        }
        if (e.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) return;
        if (plugin.islands.canBuild(p, b.getLocation())) return;
        // visitors may use player shops
        if (plugin.playerShops.isShop(b)) return;
        if (interactive(b)) {
            e.setCancelled(true);
            warn(p);
        } else if (e.getItem() != null && e.getItem().getType() != Material.AIR) {
            e.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
        }
    }

    private static boolean interactive(Block b) {
        Material m = b.getType();
        if (b.getState() instanceof InventoryHolder) return true;
        return Tag.DOORS.isTagged(m) || Tag.TRAPDOORS.isTagged(m) || Tag.FENCE_GATES.isTagged(m)
                || Tag.BUTTONS.isTagged(m) || Tag.BEDS.isTagged(m) || Tag.ANVIL.isTagged(m)
                || m == Material.LEVER || m == Material.REPEATER || m == Material.COMPARATOR
                || m == Material.NOTE_BLOCK || m == Material.JUKEBOX || m == Material.CAKE
                || m == Material.SPAWNER || m == Material.PLAYER_HEAD || m == Material.PLAYER_WALL_HEAD
                || m == Material.BEACON || m == Material.RESPAWN_ANCHOR || m == Material.COMPOSTER
                || m == Material.DAYLIGHT_DETECTOR || m == Material.FLOWER_POT || m == Material.END_PORTAL_FRAME;
    }

    // ------------------------------------------------------------------ entities
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        if (!islandWorld(e.getEntity().getWorld())) return;
        Player attacker = null;
        if (e.getDamager() instanceof Player p) attacker = p;
        else if (e.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Player p) attacker = p;
        if (attacker == null) return;
        if (e.getEntity() instanceof Player) {
            e.setCancelled(true);
            return;
        }
        if (denied(attacker, e.getEntity().getLocation())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHanging(HangingBreakByEntityEvent e) {
        if (e.getRemover() instanceof Player p && denied(p, e.getEntity().getLocation())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityInteract(PlayerInteractEntityEvent e) {
        if (denied(e.getPlayer(), e.getRightClicked().getLocation())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPickup(PlayerAttemptPickupItemEvent e) {
        Location l = e.getItem().getLocation();
        if (islandWorld(l.getWorld()) && !plugin.islands.canBuild(e.getPlayer(), l)) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) {
        if (islandWorld(e.getLocation().getWorld())) e.blockList().clear();
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        if (islandWorld(e.getBlock().getWorld())) e.blockList().clear();
    }

    @EventHandler(ignoreCancelled = true)
    public void onChangeBlock(EntityChangeBlockEvent e) {
        if (!islandWorld(e.getBlock().getWorld())) return;
        EntityType t = e.getEntityType();
        if (t == EntityType.ENDERMAN || t == EntityType.WITHER || t == EntityType.RAVAGER || t == EntityType.SILVERFISH)
            e.setCancelled(true);
        if (e.getBlock().getType() == Material.FARMLAND) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent e) {
        if (!islandWorld(e.getLocation().getWorld())) return;
        if (e.getEntityType() == EntityType.ENDER_DRAGON || e.getEntityType() == EntityType.WITHER) {
            e.setCancelled(true);
            return;
        }
        if (e.getSpawnReason() == CreatureSpawnEvent.SpawnReason.NATURAL && plugin.worlds.isSpawnArea(e.getLocation()))
            e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onFlow(BlockFromToEvent e) {
        Block from = e.getBlock(), to = e.getToBlock();
        if (!islandWorld(from.getWorld())) return;
        Island i = plugin.islands.at(from);
        if (i == null || !plugin.islands.inBounds(i, to.getWorld(), to.getX(), to.getZ())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent e) {
        if (!islandWorld(e.getBlock().getWorld())) return;
        Island i = plugin.islands.at(e.getBlock());
        if (i == null) {
            e.setCancelled(true);
            return;
        }
        for (Block b : e.getBlocks()) {
            Block t = b.getRelative(e.getDirection());
            if (!plugin.islands.inBounds(i, t.getWorld(), t.getX(), t.getZ())) {
                e.setCancelled(true);
                return;
            }
        }
    }

    // ------------------------------------------------------------------ players
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onVoid(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p) || e.getCause() != EntityDamageEvent.DamageCause.VOID) return;
        if (!islandWorld(p.getWorld())) return;
        e.setCancelled(true);
        p.setFallDistance(0);
        Island i = plugin.islands.cellIsland(p.getLocation());
        Location target;
        if (i != null) target = plugin.islands.dimensionHome(i, plugin.worlds.dim(p.getWorld()));
        else target = plugin.worlds.spawn();
        p.teleportAsync(target);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Island i = plugin.islands.main(e.getPlayer().getUniqueId());
        if (i == null) {
            for (Island a : plugin.islands.accessible(e.getPlayer().getUniqueId())) {
                i = a;
                break;
            }
        }
        e.setRespawnLocation(i != null ? plugin.islands.home(i) : plugin.worlds.spawn());
    }

    @EventHandler
    public void onSpawnLocation(AsyncPlayerSpawnLocationEvent e) {
        Location l = e.getSpawnLocation();
        if (e.isNewPlayer() || l == null || !islandWorld(l.getWorld())) e.setSpawnLocation(plugin.worlds.spawn());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onTeleport(PlayerTeleportEvent e) {
        plugin.islands.forgetBorder(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Profile prof = plugin.data.profileOrCreate(p.getUniqueId(), p.getName());
        if (prof.lastSeen == 0) {
            prof.dollars += plugin.settings.config.getDouble("economy.starting-dollars", 0);
            Text.msg(p, "<white>Welcome to SkyCore!</white> Type <green>/is</green> to create your island.");
        }
        prof.staff = p.hasPermission("skycore.staff");
        prof.lastSeen = System.currentTimeMillis();
        plugin.data.dirty(prof);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Profile prof = plugin.data.profile(e.getPlayer().getUniqueId());
        if (prof != null) {
            prof.lastSeen = System.currentTimeMillis();
            plugin.data.dirty(prof);
        }
        for (Island i : plugin.islands.accessible(e.getPlayer().getUniqueId())) {
            i.lastActive = System.currentTimeMillis();
            plugin.data.dirty(i);
        }
        plugin.islands.forgetBorder(e.getPlayer().getUniqueId());
        lastWarn.remove(e.getPlayer().getUniqueId());
    }
}
