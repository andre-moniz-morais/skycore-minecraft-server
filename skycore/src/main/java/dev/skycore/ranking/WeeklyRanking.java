package dev.skycore.ranking;

import dev.skycore.SkyCore;
import dev.skycore.data.Profile;
import dev.skycore.util.Keys;
import dev.skycore.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

/**
 * Four weekly rankings: Effort (Roses), Mastery (skill XP), Construction (net island value gain) and
 * Trade (Dollars from sell chests + wands). The week closes on Monday 00:00 (server timezone).
 * Rewards go to /rewards; never Roses. Requires 1 h of active play; staff and banned accounts excluded.
 */
public final class WeeklyRanking {
    public enum Board {
        EFFORT("effort", "Effort", "Roses earned"),
        MASTERY("mastery", "Mastery", "Skill XP gained"),
        CONSTRUCTION("construction", "Construction", "Island value gained"),
        TRADE("trade", "Trade", "Dollars from automation");

        public final String id, display, measure;

        Board(String id, String display, String measure) {
            this.id = id;
            this.display = display;
            this.measure = measure;
        }
    }

    public record Entry(UUID uuid, String name, double value, long reachedAt) {}

    private final SkyCore plugin;
    private TextDisplay hologram;

    public WeeklyRanking(SkyCore plugin) {
        this.plugin = plugin;
    }

    public String currentWeek() {
        return LocalDate.now(plugin.settings.zone).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString();
    }

    private void rolloverIfNeeded() {
        String cur = currentWeek();
        var g = plugin.data.global;
        if (cur.equals(g.currentWeek)) return;
        String old = g.currentWeek;
        g.currentWeek = cur;
        plugin.data.dirtyGlobal();
        if (old != null && !old.isEmpty()) close(old);
    }

    public void ensureWeek(Profile p) {
        rolloverIfNeeded();
        String cur = currentWeek();
        if (cur.equals(p.weekly.week)) {
            if (p.weekly.valueStart < 0) p.weekly.valueStart = plugin.islands.totalOwnedValue(p.uuid);
            return;
        }
        p.weekly.week = cur;
        p.weekly.roses = 0;
        p.weekly.xp = 0;
        p.weekly.trade = 0;
        p.weekly.activeSeconds = 0;
        p.weekly.reachedAt.clear();
        p.weekly.valueStart = plugin.islands.totalOwnedValue(p.uuid);
        plugin.data.dirty(p);
    }

    private double value(Profile p, Board b) {
        return switch (b) {
            case EFFORT -> p.weekly.roses;
            case MASTERY -> p.weekly.xp;
            case TRADE -> p.weekly.trade;
            case CONSTRUCTION -> plugin.islands.totalOwnedValue(p.uuid) - Math.max(0, p.weekly.valueStart);
        };
    }

    public List<Entry> standings(String week, Board b) {
        long minActive = plugin.settings.config.getLong("ranking.min-active-minutes", 60) * 60L;
        List<Entry> out = new ArrayList<>();
        for (Profile p : plugin.data.profiles.values()) {
            if (!week.equals(p.weekly.week) || p.banned || p.staff) continue;
            if (p.weekly.activeSeconds < minActive) continue;
            double v = value(p, b);
            if (v <= 0) continue;
            long reached = p.weekly.reachedAt.getOrDefault(b.id, Long.MAX_VALUE);
            out.add(new Entry(p.uuid, p.name, v, reached));
        }
        out.sort(Comparator.comparingDouble(Entry::value).reversed().thenComparingLong(Entry::reachedAt));
        return out;
    }

    private void close(String week) {
        Map<String, List<Map<String, Object>>> snapshot = new LinkedHashMap<>();
        StringBuilder announce = new StringBuilder("**Weekly rankings - week of " + week + "**\n");
        List<Map<?, ?>> tiers = plugin.settings.goals.getMapList("ranking-rewards");
        for (Board b : Board.values()) {
            List<Entry> list = standings(week, b);
            List<Map<String, Object>> rows = new ArrayList<>();
            for (int pos = 1; pos <= list.size(); pos++) {
                Entry e = list.get(pos - 1);
                rows.add(Map.of("pos", pos, "name", e.name(), "uuid", e.uuid().toString(), "value", e.value()));
                for (Map<?, ?> t : tiers) {
                    int from = ((Number) t.get("from")).intValue(), to = ((Number) t.get("to")).intValue();
                    if (pos < from || pos > to) continue;
                    @SuppressWarnings("unchecked")
                    List<String> rewards = (List<String>) t.get("rewards");
                    for (String r : rewards) plugin.rewards.grantToBox(e.uuid(), r, "ranking");
                }
                if (pos <= 3) announce.append(b.display).append(" #").append(pos).append(": ").append(e.name())
                        .append(" (").append(Text.compact(e.value())).append(")\n");
            }
            snapshot.put(b.id, rows);
        }
        plugin.data.putSnapshot(week, snapshot);
        Bukkit.broadcast(Text.mm(Text.PREFIX + "<gold>The weekly rankings closed! <gray>Winners can claim rewards with <white>/rewards</white>."));
        webhook(announce.toString());
        plugin.getLogger().info("Closed weekly rankings for " + week);
    }

    private void webhook(String content) {
        String url = plugin.settings.config.getString("ranking.webhook-url", "");
        if (url == null || url.isBlank()) return;
        com.google.gson.JsonObject o = new com.google.gson.JsonObject();
        o.addProperty("content", content);
        String json = o.toString();
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)).build();
        HttpClient.newHttpClient().sendAsync(req, HttpResponse.BodyHandlers.discarding())
                .exceptionally(ex -> {
                    plugin.getLogger().warning("Ranking webhook failed: " + ex.getMessage());
                    return null;
                });
    }

    public void tick() {
        rolloverIfNeeded();
        updateHologram();
    }

    // ------------------------------------------------------------------ display
    public void show(Player p) {
        String week = currentWeek();
        Text.raw(p, "<gradient:#ff5e8a:#ffb86c><bold>Weekly Rankings</bold></gradient> <gray>(week of " + week + ", closes Monday 00:00)");
        for (Board b : Board.values()) {
            List<Entry> list = standings(week, b);
            Text.raw(p, "<gold>" + b.display + " <gray>- " + b.measure);
            for (int i = 0; i < Math.min(5, list.size()); i++)
                Text.raw(p, "  <yellow>#" + (i + 1) + "</yellow> <white>" + Text.escape(list.get(i).name()) + " <gray>" + Text.compact(list.get(i).value()));
            if (list.isEmpty()) Text.raw(p, "  <dark_gray>No eligible players yet");
            for (int i = 0; i < list.size(); i++)
                if (list.get(i).uuid().equals(p.getUniqueId()) && i >= 5)
                    Text.raw(p, "  <gray>You: #" + (i + 1) + " " + Text.compact(list.get(i).value()));
        }
        Profile prof = plugin.data.profile(p.getUniqueId());
        long minActive = plugin.settings.config.getLong("ranking.min-active-minutes", 60);
        if (prof.weekly.activeSeconds < minActive * 60)
            Text.raw(p, "<dark_gray>You need " + minActive + " active minutes this week to be ranked (" + prof.weekly.activeSeconds / 60 + " so far).");
    }

    private void updateHologram() {
        if (!plugin.settings.config.getBoolean("ranking.hologram.enabled", true)) return;
        var c = plugin.settings.config;
        Location loc = new Location(plugin.worlds.overworld(), c.getDouble("ranking.hologram.x"), c.getDouble("ranking.hologram.y"), c.getDouble("ranking.hologram.z"));
        if (!loc.getWorld().isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4)) return;
        if (hologram == null || !hologram.isValid()) {
            hologram = loc.getWorld().spawn(loc, TextDisplay.class, t -> {
                t.setPersistent(false);
                t.setBillboard(Display.Billboard.VERTICAL);
                t.getPersistentDataContainer().set(Keys.HOLO, PersistentDataType.BYTE, (byte) 1);
                t.setLineWidth(240);
            });
        }
        StringBuilder sb = new StringBuilder("<gradient:#ff5e8a:#ffb86c><bold>Weekly Rankings</bold></gradient>");
        String week = currentWeek();
        for (Board b : Board.values()) {
            sb.append("\n<gold>").append(b.display).append("</gold>");
            List<Entry> list = standings(week, b);
            for (int i = 0; i < Math.min(3, list.size()); i++)
                sb.append("\n<yellow>#").append(i + 1).append(" <white>").append(Text.escape(list.get(i).name()))
                        .append(" <gray>").append(Text.compact(list.get(i).value()));
            if (list.isEmpty()) sb.append("\n<dark_gray>-");
        }
        sb.append("\n<gray>/top");
        hologram.text(Text.mm(sb.toString()));
    }

    public void removeHologram() {
        if (hologram != null) hologram.remove();
    }
}
