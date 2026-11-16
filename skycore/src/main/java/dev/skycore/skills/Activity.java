package dev.skycore.skills;

import dev.skycore.SkyCore;
import dev.skycore.data.Profile;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.*;

/**
 * Anti-AFK: a player is idle after N seconds without turning their head or walking on their own
 * (being pushed by water or riding a vehicle does not count). Also detects macro-like perfectly
 * regular action intervals and alerts staff.
 */
public final class Activity implements Listener {
    private final SkyCore plugin;
    private final Map<UUID, Long> lastActive = new HashMap<>();
    private final Map<String, ArrayDeque<Long>> samples = new HashMap<>();
    private final Map<UUID, Long> lastAlert = new HashMap<>();

    public Activity(SkyCore plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Location f = e.getFrom(), t = e.getTo();
        boolean rotated = f.getYaw() != t.getYaw() || f.getPitch() != t.getPitch();
        boolean walked = (f.getX() != t.getX() || f.getZ() != t.getZ() || f.getY() != t.getY());
        Player p = e.getPlayer();
        if (rotated || (walked && !p.isInWater() && !p.isInsideVehicle())) lastActive.put(p.getUniqueId(), System.currentTimeMillis());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        lastActive.put(e.getPlayer().getUniqueId(), System.currentTimeMillis());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID u = e.getPlayer().getUniqueId();
        lastActive.remove(u);
        samples.keySet().removeIf(k -> k.startsWith(u.toString()));
    }

    public boolean isAfk(Player p) {
        long idle = plugin.settings.config.getLong("afk.idle-seconds", 60) * 1000L;
        return System.currentTimeMillis() - lastActive.getOrDefault(p.getUniqueId(), 0L) > idle;
    }

    /** Records an action timestamp and raises a staff alert on suspiciously regular intervals. */
    public void record(Player p, Skill s) {
        int n = plugin.settings.config.getInt("afk.pattern-samples", 40);
        String key = p.getUniqueId() + ":" + s;
        ArrayDeque<Long> q = samples.computeIfAbsent(key, k -> new ArrayDeque<>());
        q.addLast(System.currentTimeMillis());
        while (q.size() > n + 1) q.removeFirst();
        if (q.size() < n + 1) return;
        long[] iv = new long[n];
        Iterator<Long> it = q.iterator();
        long prev = it.next();
        for (int i = 0; it.hasNext(); i++) {
            long cur = it.next();
            iv[i] = cur - prev;
            prev = cur;
        }
        double mean = Arrays.stream(iv).average().orElse(0);
        if (mean < 150) return; // insta-mining with haste is naturally fast and regular
        double var = 0;
        for (long v : iv) var += (v - mean) * (v - mean);
        double sd = Math.sqrt(var / n);
        if (sd <= plugin.settings.config.getDouble("afk.pattern-max-deviation-ms", 12)) {
            long cd = plugin.settings.config.getLong("afk.pattern-alert-cooldown-minutes", 10) * 60_000L;
            long now = System.currentTimeMillis();
            if (now - lastAlert.getOrDefault(p.getUniqueId(), 0L) > cd) {
                lastAlert.put(p.getUniqueId(), now);
                plugin.alerts.staff("macro", p.getName() + " has " + n + " " + s.display + " actions with near-identical intervals (avg "
                        + Math.round(mean) + "ms, sd " + String.format("%.1f", sd) + "ms)");
            }
        }
    }

    /** Called every minute: credits active (non-idle) play time for ranking eligibility. */
    public void tickMinute() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (isAfk(p)) continue;
            Profile prof = plugin.data.profile(p.getUniqueId());
            if (prof == null) continue;
            plugin.ranking.ensureWeek(prof);
            prof.weekly.activeSeconds += 60;
            plugin.data.dirty(prof);
        }
    }
}
