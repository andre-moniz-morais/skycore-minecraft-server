package dev.skycore.goals;

import dev.skycore.SkyCore;
import dev.skycore.data.Island;
import dev.skycore.data.Profile;
import dev.skycore.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * Ascension (Tier 10): resets islands, Dollars, minions, spawners, upgrades and generators; keeps skills,
 * Roses, pets, spawner unlocks and titles. +1 star (sell +3%, XP +5%), new island starts at level 1 + stars (max 5).
 */
public final class Prestige {
    private final SkyCore plugin;

    public Prestige(SkyCore plugin) {
        this.plugin = plugin;
    }

    public void run(Player p, boolean confirm) {
        Profile prof = plugin.data.profile(p.getUniqueId());
        int maxTier = plugin.path.tiers.lastKey();
        int maxStars = plugin.settings.config.getInt("prestige.max-stars", 10);
        if (prof.tier < maxTier) {
            Text.msg(p, "<red>Ascension unlocks at Tier " + maxTier + " (" + plugin.path.name(maxTier) + "). You are Tier " + prof.tier + ".");
            return;
        }
        if (prof.stars >= maxStars) {
            Text.msg(p, "<yellow>You already have the maximum of " + maxStars + " stars.");
            return;
        }
        if (!confirm) {
            Text.msg(p, "<gold>Ascension</gold> will <red>reset</red> all your islands, Dollars, minions, spawners, upgrades and generators.");
            Text.msg(p, "You keep skills, Roses, pets, spawner unlocks and titles, and gain <yellow>★" + (prof.stars + 1) + "</yellow>.");
            Text.msg(p, "<red>Type <white>/prestige confirm</white> to ascend.");
            return;
        }
        List<Island> owned = new ArrayList<>(plugin.islands.owned(p.getUniqueId()));
        for (Island i : owned) plugin.islands.retire(i);
        prof.islands.clear();
        plugin.eco.setDollars(p.getUniqueId(), 0, "prestige_reset");
        prof.stars++;
        prof.tier = 1;
        prof.petSlot2 = true;
        prof.weekly.valueStart = 0;
        plugin.data.dirty(prof);
        int start = plugin.islands.startLevel(prof);
        Bukkit.broadcast(Text.mm(Text.PREFIX + "<gold>" + p.getName() + " ascended! <yellow>★" + prof.stars));
        plugin.islands.create(p, start, i -> Text.msg(p, "<green>Your new island starts at level " + i.level + "."));
    }
}
