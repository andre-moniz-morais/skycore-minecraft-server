package dev.skycore.auto;

import dev.skycore.SkyCore;
import dev.skycore.data.Island;
import dev.skycore.data.Profile;
import dev.skycore.shop.Pricing;
import dev.skycore.util.Pos;
import dev.skycore.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.entity.Player;

import java.util.*;

/**
 * Sell chests: every few seconds each loaded sell chest of an active island sells its contents.
 * Minions, spawners and chunk hoppers linked to a sell chest sell through {@link #deliver}.
 * Money always goes to the island owner; notifications are grouped in a summary per minute.
 */
public final class SellChestService {
    private final SkyCore plugin;
    private final Map<UUID, double[]> summary = new HashMap<>();

    public SellChestService(SkyCore plugin) {
        this.plugin = plugin;
    }

    public void tick() {
        for (Island i : plugin.islands.active()) {
            Iterator<String> it = i.sellChests.iterator();
            while (it.hasNext()) {
                Pos pos = Pos.parse(it.next());
                if (!pos.isLoaded()) continue;
                Block b = pos.block();
                if (b == null) continue;
                if (b.getType() != Material.CHEST || !(b.getState() instanceof Chest chest)) {
                    it.remove();
                    plugin.data.dirty(i);
                    continue;
                }
                Pricing.Sale sale = plugin.pricing.sellInventory(chest.getBlockInventory(), i.owner, i, 1.0, true);
                if (sale.total() > 0) credit(i, sale.total(), sale.items(), "sellchest");
            }
        }
    }

    /** Pays the island owner for an automated sale and records stats / missions. */
    public void credit(Island i, double value, long items, String source) {
        if (value <= 0) return;
        plugin.eco.deposit(i.owner, value, source);
        i.sellChestTotal += value;
        plugin.data.dirty(i);
        Profile p = plugin.data.profile(i.owner);
        if (p != null) {
            plugin.ranking.ensureWeek(p);
            p.totalSold += value;
            p.weekly.trade += value;
            p.weekly.reachedAt.put("trade", System.currentTimeMillis());
            plugin.data.dirty(p);
        }
        plugin.islandMissions.progress(i, "sellchest_dollars", (long) Math.floor(value));
        double[] s = summary.computeIfAbsent(i.owner, k -> new double[2]);
        s[0] += value;
        s[1] += items;
    }

    /**
     * Delivers automated production: sells it through a sell chest within the link radius.
     * Returns false if no sell chest is linked (caller stores the items instead).
     */
    public boolean deliver(Island i, Pos from, Material m, long amount, String source) {
        if (amount <= 0) return true;
        if (linked(i, from) == null) return false;
        double v = plugin.pricing.batchValue(m, amount, i.owner, i);
        if (v <= 0) return false;
        credit(i, v, amount, source);
        return true;
    }

    public Pos linked(Island i, Pos from) {
        int r = plugin.settings.config.getInt("automation.link-radius", 5);
        double best = (double) r * r + 0.01;
        Pos found = null;
        for (String k : i.sellChests) {
            Pos p = Pos.parse(k);
            double d = p.distSq(from);
            if (d <= best) {
                best = d;
                found = p;
            }
        }
        return found;
    }

    public void flushSummaries() {
        for (Map.Entry<UUID, double[]> e : summary.entrySet()) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p != null && e.getValue()[0] > 0)
                Text.msg(p, "<gray>Automation sold <white>" + Text.num(e.getValue()[1]) + "</white> items for " + Text.dollars(e.getValue()[0]) + " <gray>in the last minute.");
        }
        summary.clear();
    }

    public boolean isSellChest(Block b) {
        Island i = plugin.islands.at(b);
        return i != null && i.sellChests.contains(Pos.key(b));
    }
}
