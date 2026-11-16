package dev.skycore.island;

import dev.skycore.SkyCore;
import dev.skycore.config.Settings;
import dev.skycore.data.Island;
import dev.skycore.gen.GeneratorService;
import dev.skycore.util.Text;
import dev.skycore.world.Dim;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * Island upgrades. Every upgrade needs minimum island value + Roses + Dollars at the same time,
 * is bought only by the owner with the owner's own balance, and never regresses.
 */
public final class UpgradeService {
    public enum Kind {
        LEVEL("Island Level"), SELL("Sell Multiplier"), GROWTH("Crop Growth"),
        GEN_OVERWORLD("Overworld Generator"), GEN_NETHER("Nether Generator"), GEN_END("End Generator");

        public final String display;

        Kind(String display) {
            this.display = display;
        }
    }

    public record Offer(Kind kind, int current, int next, Settings.Cost cost, int islandLevelReq, boolean maxed, List<String> missing) {}

    private final SkyCore plugin;

    public UpgradeService(SkyCore plugin) {
        this.plugin = plugin;
    }

    private static Dim dimOf(Kind k) {
        return switch (k) {
            case GEN_NETHER -> Dim.NETHER;
            case GEN_END -> Dim.END;
            default -> Dim.OVERWORLD;
        };
    }

    public int current(Island i, Kind k) {
        return switch (k) {
            case LEVEL -> i.level;
            case SELL -> i.sellLevel;
            case GROWTH -> i.growthLevel;
            case GEN_OVERWORLD, GEN_NETHER, GEN_END -> GeneratorService.level(i, dimOf(k));
        };
    }

    public Offer offer(Island i, Kind k) {
        int cur = current(i, k);
        int next = cur + 1;
        Settings.Cost cost = null;
        int req = 0;
        switch (k) {
            case LEVEL -> {
                Settings.IslandLevel l = plugin.settings.islandLevels.get(next);
                if (l != null) cost = l.cost();
            }
            case SELL -> {
                Settings.SimpleUpgrade u = plugin.settings.sellMultiplier.get(next);
                if (u != null) {
                    cost = u.cost();
                    req = u.islandLevel();
                }
            }
            case GROWTH -> {
                Settings.SimpleUpgrade u = plugin.settings.cropGrowth.get(next);
                if (u != null) {
                    cost = u.cost();
                    req = u.islandLevel();
                }
            }
            default -> {
                Settings.GenLevel g = plugin.settings.gens.get(dimOf(k)).get(next);
                if (g != null) {
                    cost = g.cost();
                    req = g.islandLevel();
                }
            }
        }
        if (cost == null) return new Offer(k, cur, cur, Settings.Cost.FREE, 0, true, List.of());
        List<String> missing = new ArrayList<>();
        double value = plugin.islands.value(i);
        if (req > i.level) missing.add("Island level " + req);
        if (value < cost.value()) missing.add("Island value " + Text.compact(cost.value()) + " (now " + Text.compact(value) + ")");
        if (plugin.eco.roses(i.owner) < cost.roses()) missing.add(Text.num(cost.roses()) + " Roses");
        if (!plugin.eco.hasDollars(i.owner, cost.dollars())) missing.add(Text.money(cost.dollars()));
        return new Offer(k, cur, next, cost, req, false, missing);
    }

    public boolean buy(Player p, Island i, Kind k) {
        if (!i.owner.equals(p.getUniqueId())) {
            Text.msg(p, "<red>Only the island owner can buy upgrades (with their own balance).");
            return false;
        }
        Offer o = offer(i, k);
        if (o.maxed()) {
            Text.msg(p, "<yellow>" + k.display + " is already maxed.");
            return false;
        }
        if (!o.missing().isEmpty()) {
            Text.msg(p, "<red>Missing: <white>" + String.join("<gray>, <white>", o.missing()));
            return false;
        }
        if (!plugin.eco.charge(i.owner, o.cost().roses(), o.cost().dollars(), "upgrade:" + k.name().toLowerCase())) {
            Text.msg(p, "<red>Payment failed.");
            return false;
        }
        switch (k) {
            case LEVEL -> i.level = o.next();
            case SELL -> i.sellLevel = o.next();
            case GROWTH -> i.growthLevel = o.next();
            default -> GeneratorService.setLevel(i, dimOf(k), o.next());
        }
        plugin.data.dirty(i);
        plugin.islands.forgetBorder(p.getUniqueId());
        Text.msg(p, "<green>" + k.display + " upgraded to level " + o.next() + "!");
        if (k == Kind.LEVEL) {
            int nether = plugin.settings.islands.getInt("unlocks.nether-portal-level", 3);
            int end = plugin.settings.islands.getInt("unlocks.end-portal-level", 6);
            if (o.next() == 2) Text.msg(p, "<gray>Unlocked: chunk hoppers, spawners and player shops.");
            if (o.next() == nether) Text.msg(p, "<gray>Unlocked: <red>Nether portal</red>. Build an obsidian portal on your island.");
            if (o.next() == end) Text.msg(p, "<gray>Unlocked: <light_purple>End portal</light_purple> (12 frames + 12 eyes of ender).");
            for (var ex : plugin.settings.extraIslands.values())
                if (ex.mainLevel() == o.next() && plugin.islands.main(p.getUniqueId()) == i)
                    Text.msg(p, "<gray>Unlocked: island #" + ex.number() + " (/is create).");
            if (o.next() == plugin.settings.islands.getInt("unlocks.prestige-level", 10)) Text.msg(p, "<gold>Max level! Prestige eligibility reached.");
        }
        plugin.islandMissions.check(i);
        plugin.path.check(p);
        return true;
    }
}
