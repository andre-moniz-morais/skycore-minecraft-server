package dev.skycore.hud;

import dev.skycore.SkyCore;
import dev.skycore.data.Island;
import dev.skycore.data.Profile;
import dev.skycore.util.Text;
import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import io.papermc.paper.scoreboard.numbers.NumberFormat;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;

import java.util.*;

/** Sidebar with balances and the next missing Path requirement, plus chat prefixes (stars + title). */
public final class Hud implements Listener {
    private final SkyCore plugin;
    private final Map<UUID, Scoreboard> boards = new HashMap<>();
    private final Map<UUID, Integer> lineCount = new HashMap<>();

    public Hud(SkyCore plugin) {
        this.plugin = plugin;
    }

    public void tick() {
        if (!plugin.settings.config.getBoolean("hud.scoreboard", true)) return;
        for (Player p : Bukkit.getOnlinePlayers()) update(p);
    }

    private void update(Player p) {
        Profile prof = plugin.data.profile(p.getUniqueId());
        if (prof == null) return;
        if (!prof.scoreboard) {
            if (boards.remove(p.getUniqueId()) != null) p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
            return;
        }
        Scoreboard sb = boards.computeIfAbsent(p.getUniqueId(), k -> {
            Scoreboard s = Bukkit.getScoreboardManager().getNewScoreboard();
            Objective o = s.registerNewObjective("skycore", Criteria.DUMMY, Text.mm(plugin.settings.config.getString("hud.server-name", "SkyCore")));
            o.setDisplaySlot(DisplaySlot.SIDEBAR);
            o.numberFormat(NumberFormat.blank());
            return s;
        });
        if (p.getScoreboard() != sb) p.setScoreboard(sb);
        Objective o = sb.getObjective("skycore");
        List<String> lines = lines(p, prof);
        int old = lineCount.getOrDefault(p.getUniqueId(), 0);
        for (int i = lines.size(); i < old; i++) sb.resetScores("l" + i);
        for (int i = 0; i < lines.size(); i++) {
            var score = o.getScore("l" + i);
            score.setScore(lines.size() - i);
            score.customName(Text.mm(lines.get(i)));
        }
        lineCount.put(p.getUniqueId(), lines.size());
    }

    private List<String> lines(Player p, Profile prof) {
        List<String> l = new ArrayList<>();
        l.add("<gray>Dollars <green>" + Text.money(prof.dollars));
        l.add("<gray>Roses <#ff5e8a>" + Text.num(prof.roses) + " ✿");
        l.add(" ");
        Island i = plugin.islandMenus.context(p);
        if (i != null) {
            l.add("<gray>Island <white>Lv " + i.level + " <dark_gray>| <gray>value <white>" + Text.compact(plugin.islands.value(i)));
        }
        l.add("<gray>Tier <gold>" + prof.tier + " " + plugin.path.name(prof.tier) + (prof.stars > 0 ? " <yellow>★" + prof.stars : ""));
        l.add("  ");
        l.add("<yellow>Next goal");
        if (i == null && plugin.islands.accessible(p.getUniqueId()).isEmpty()) {
            l.add("<white>Create your island: /is");
        } else {
            String req = plugin.path.nextMissing(p.getUniqueId());
            if (req == null) {
                if (plugin.path.tier(prof.tier + 1) == null) l.add("<white>Ascend: /prestige");
                else l.add("<white>Tier up! /path");
            } else {
                double[] st = plugin.path.state(p.getUniqueId(), req);
                l.add("<white>" + plugin.path.describe(req));
                if (st[1] > 1) l.add(Text.progressBar(st[0] / st[1], 14) + " <gray>" + Text.compact(st[0]) + "/" + Text.compact(st[1]));
            }
        }
        l.add("   ");
        l.add("<dark_gray>/path /missions /is");
        return l;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        boards.remove(e.getPlayer().getUniqueId());
        lineCount.remove(e.getPlayer().getUniqueId());
    }

    public void cleanTitles() {
        long now = System.currentTimeMillis();
        for (Profile p : plugin.data.profiles.values()) {
            if (p.titleExpiry.isEmpty()) continue;
            Iterator<Map.Entry<String, Long>> it = p.titleExpiry.entrySet().iterator();
            while (it.hasNext()) {
                var e = it.next();
                if (e.getValue() > now) continue;
                p.titles.remove(e.getKey());
                if (e.getKey().equals(p.activeTitle)) p.activeTitle = null;
                it.remove();
                plugin.data.dirty(p);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) {
        Profile prof = plugin.data.profile(e.getPlayer().getUniqueId());
        String stars = prof != null && prof.stars > 0 ? "<yellow>★" + prof.stars + "</yellow> " : "";
        String title = prof != null && prof.activeTitle != null ? "<dark_gray>[</dark_gray>" + prof.activeTitle + "<reset><dark_gray>]</dark_gray> " : "";
        Component prefix = Text.mm(stars + title);
        e.renderer(ChatRenderer.viewerUnaware((source, name, message) ->
                prefix.append(name.colorIfAbsent(net.kyori.adventure.text.format.NamedTextColor.WHITE))
                        .append(Text.mm("<dark_gray> » </dark_gray>")).append(message.colorIfAbsent(net.kyori.adventure.text.format.NamedTextColor.GRAY))));
    }
}
