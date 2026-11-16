package dev.skycore.island;

import dev.skycore.SkyCore;
import dev.skycore.data.Island;
import dev.skycore.data.Profile;
import dev.skycore.util.Text;
import dev.skycore.world.Dim;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPortalEnterEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.PortalCreateEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Portals are handled entirely by SkyCore: Overworld island <-> its own Nether / End mini-island.
 * Nether from island level 3, End from level 6. Vanilla portal travel is disabled in island worlds.
 */
public final class PortalListener implements Listener {
    private final SkyCore plugin;
    private final Map<UUID, Long> enteredAt = new HashMap<>();
    private final Map<UUID, Long> lastSeenInPortal = new HashMap<>();
    private final Map<UUID, Long> cooldown = new HashMap<>();

    public PortalListener(SkyCore plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPortalEnter(EntityPortalEnterEvent e) {
        if (!plugin.worlds.isIslandWorld(e.getLocation().getWorld())) return;
        e.setCancelled(true);
        if (!(e.getEntity() instanceof Player p)) return;
        UUID u = p.getUniqueId();
        long now = System.currentTimeMillis();
        if (now < cooldown.getOrDefault(u, 0L)) return;
        Material type = e.getLocation().getBlock().getType();
        boolean nether = type == Material.NETHER_PORTAL;
        if (nether) {
            long last = lastSeenInPortal.getOrDefault(u, 0L);
            lastSeenInPortal.put(u, now);
            if (now - last > 1000) enteredAt.put(u, now);
            long delay = plugin.settings.config.getLong("islands.nether-portal-delay-seconds", 2) * 1000L;
            if (now - enteredAt.getOrDefault(u, now) < delay) return;
        }
        cooldown.put(u, now + 3000);
        enteredAt.remove(u);
        Dim from = plugin.worlds.dim(p.getWorld());
        Island i = plugin.islands.cellIsland(p.getLocation());
        if (i == null) return;
        if (from != Dim.OVERWORLD) {
            plugin.islands.teleport(p, plugin.islands.home(i));
            return;
        }
        if (!i.isMember(u)) {
            Text.msg(p, "<red>Only members of this island can use its portals.");
            return;
        }
        if (nether) {
            int need = plugin.settings.islands.getInt("unlocks.nether-portal-level", 3);
            if (i.level < need) {
                Text.msg(p, "<red>The Nether unlocks at island level " + need + ".");
                return;
            }
            plugin.islands.ensureDimension(i, Dim.NETHER, () -> {
                plugin.islands.teleport(p, plugin.islands.dimensionHome(i, Dim.NETHER));
                Profile prof = plugin.data.profile(u);
                if (!prof.visitedNether) {
                    prof.visitedNether = true;
                    plugin.data.dirty(prof);
                }
                if (!i.netherVisited) {
                    i.netherVisited = true;
                    plugin.data.dirty(i);
                }
                plugin.islandMissions.check(i);
            });
        } else if (type == Material.END_PORTAL) {
            int need = plugin.settings.islands.getInt("unlocks.end-portal-level", 6);
            if (i.level < need) {
                Text.msg(p, "<red>The End unlocks at island level " + need + ".");
                return;
            }
            plugin.islands.ensureDimension(i, Dim.END, () -> {
                if (!i.endPortal) {
                    i.endPortal = true;
                    plugin.data.dirty(i);
                    Text.msg(p, "<light_purple>End portal activated for this island!");
                }
                plugin.islands.teleport(p, plugin.islands.dimensionHome(i, Dim.END));
                plugin.islandMissions.check(i);
            });
        }
    }

    @EventHandler
    public void onPlayerPortal(PlayerPortalEvent e) {
        if (plugin.worlds.isIslandWorld(e.getFrom().getWorld())) e.setCancelled(true);
    }

    @EventHandler
    public void onEntityPortal(EntityPortalEvent e) {
        if (plugin.worlds.isIslandWorld(e.getFrom().getWorld())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onCreate(PortalCreateEvent e) {
        if (!plugin.worlds.isIslandWorld(e.getWorld())) return;
        if (plugin.worlds.dim(e.getWorld()) != Dim.OVERWORLD || e.getReason() != PortalCreateEvent.CreateReason.FIRE) return;
        if (e.getBlocks().isEmpty()) return;
        Island i = plugin.islands.at(e.getBlocks().getFirst().getLocation());
        int need = plugin.settings.islands.getInt("unlocks.nether-portal-level", 3);
        if (i == null || i.level < need) {
            e.setCancelled(true);
            if (e.getEntity() instanceof Player p) Text.msg(p, "<red>Nether portals work from island level " + need + ".");
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID u = e.getPlayer().getUniqueId();
        enteredAt.remove(u);
        lastSeenInPortal.remove(u);
        cooldown.remove(u);
    }
}
