package dev.skycore.skills;

import dev.skycore.SkyCore;
import dev.skycore.data.Profile;
import dev.skycore.util.Text;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.time.LocalDate;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * XP, levels and Roses payouts. This is the ONLY place in the plugin where Roses are created
 * (apart from admin commands).
 */
public final class SkillService {
    private final SkyCore plugin;

    public SkillService(SkyCore plugin) {
        this.plugin = plugin;
    }

    private ConfigurationSection cfg() {
        return plugin.settings.skills;
    }

    public int maxLevel() {
        return cfg().getInt("curve.max-level", 100);
    }

    public double xpForLevel(int level) {
        if (level <= 1) return 0;
        return cfg().getDouble("curve.base", 50) * Math.pow(level, cfg().getDouble("curve.exponent", 2.2));
    }

    public int levelOf(double xp) {
        int lvl = 1;
        int max = maxLevel();
        while (lvl < max && xp + 1e-9 >= xpForLevel(lvl + 1)) lvl++;
        return lvl;
    }

    public int level(Profile p, Skill s) {
        return levelOf(p.skill(s).xp);
    }

    public int level(java.util.UUID u, Skill s) {
        Profile p = plugin.data.profile(u);
        return p == null ? 1 : level(p, s);
    }

    public double progress(Profile p, Skill s) {
        int l = level(p, s);
        if (l >= maxLevel()) return 1;
        double a = xpForLevel(l), b = xpForLevel(l + 1);
        return (p.skill(s).xp - a) / (b - a);
    }

    public int payoutEvery(Skill s) {
        return cfg().getInt(s.name().toLowerCase() + ".payout-every", 50);
    }

    public long rosesPerPayout(int level) {
        return cfg().getLong("roses.base", 1) + level / Math.max(1, cfg().getInt("roses.per-levels", 20));
    }

    public double sellBonus(Profile p, Skill s) {
        return Math.min(0.5, level(p, s) * cfg().getDouble("perks.sell-bonus-per-level", 0.005));
    }

    public double doubleDropChance(Profile p, Skill s) {
        int lvl = level(p, s);
        TreeMap<Integer, Double> map = new TreeMap<>();
        ConfigurationSection dd = cfg().getConfigurationSection("perks.double-drop");
        if (dd != null) for (String k : dd.getKeys(false)) map.put(Integer.parseInt(k), dd.getDouble(k));
        Map.Entry<Integer, Double> e = map.floorEntry(lvl);
        return e == null ? 0 : e.getValue();
    }

    public boolean rollDouble(Profile p, Skill s) {
        double c = doubleDropChance(p, s);
        return c > 0 && ThreadLocalRandom.current().nextDouble() < c;
    }

    public boolean hasUtilityPerk(Profile p, Skill s) {
        return level(p, s) >= cfg().getInt("perks.utility-level", 10);
    }

    private void resetDaily(Profile p) {
        String today = LocalDate.now(plugin.settings.zone).toString();
        if (!today.equals(p.capDay)) {
            p.capDay = today;
            for (Profile.SkillData d : p.skills.values()) d.payoutsToday = 0;
        }
    }

    /**
     * An active player action. Applies anti-AFK, soft cap, XP bonuses, pet XP, payouts,
     * daily mission progress and weekly ranking stats.
     *
     * @return true if the action counted
     */
    public boolean action(Player player, Skill s, double baseXp) {
        if (player.getGameMode() == org.bukkit.GameMode.CREATIVE || player.getGameMode() == org.bukkit.GameMode.SPECTATOR) return false;
        if (plugin.activity.isAfk(player)) {
            Text.bar(player, "<gray>You are idle - move to earn XP and Roses.");
            return false;
        }
        Profile p = plugin.data.profile(player.getUniqueId());
        if (p == null) return false;
        plugin.activity.record(player, s);
        resetDaily(p);
        plugin.ranking.ensureWeek(p);
        Profile.SkillData d = p.skill(s);
        int cap = cfg().getInt("daily-cap.payouts", 120);
        double factor = d.payoutsToday >= cap ? cfg().getDouble("daily-cap.reduced-factor", 0.25) : 1.0;

        double bonus = 1 + plugin.pets.skillXpBonus(p, s)
                + p.stars * plugin.settings.config.getDouble("prestige.xp-bonus-per-star", 0.05);
        double xp = baseXp * bonus * factor;
        int before = level(p, s);
        d.xp += xp;
        int after = level(p, s);
        p.weekly.xp += xp;
        p.weekly.reachedAt.put("mastery", System.currentTimeMillis());
        plugin.pets.onSkillXp(player, p, xp);

        d.actions++;
        long rosesGained = 0;
        if (d.actions >= payoutEvery(s)) {
            d.actions = 0;
            d.payoutsToday++;
            d.roseFraction += rosesPerPayout(after) * factor;
            rosesGained = (long) Math.floor(d.roseFraction + 1e-9);
            d.roseFraction -= rosesGained;
            if (rosesGained > 0) {
                plugin.eco.addRoses(p.uuid, rosesGained, "skill:" + s.name().toLowerCase());
                p.weekly.roses += rosesGained;
                p.weekly.reachedAt.put("effort", System.currentTimeMillis());
            }
        }
        plugin.data.dirty(p);

        if (rosesGained > 0) {
            Text.bar(player, "<#ff5e8a>+" + rosesGained + " ✿ Roses <gray>(" + s.display + ")" + (factor < 1 ? " <dark_gray>[daily cap]" : ""));
            player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.4f);
        } else {
            Text.bar(player, "<aqua>+" + Text.num(Math.round(xp * 10) / 10.0) + " " + s.display + " XP <gray>| Lv " + after + " "
                    + Text.progressBar(progress(p, s), 20) + " <gray>" + d.actions + "/" + payoutEvery(s));
        }
        if (after > before) onLevelUp(player, p, s, after);
        plugin.daily.progress(player, p, s, 1);
        return true;
    }

    private void onLevelUp(Player player, Profile p, Skill s, int level) {
        Text.msg(player, "<gold>" + s.display + " level up! <yellow>" + level + "</yellow> <gray>Sell bonus +" + Text.num(sellBonus(p, s) * 100) + "%");
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.0f);
        int utility = cfg().getInt("perks.utility-level", 10);
        if (level == utility) {
            String perk = switch (s) {
                case MINING -> "Haste I on your islands";
                case FARMING -> "auto-replant for normal crops";
                case FISHING -> "20% shorter fishing wait";
                case COMBAT -> "mob drop magnet";
            };
            Text.msg(player, "<green>Perk unlocked: <white>" + perk);
        }
        if (level == 25 || level == 50 || level == 75 || level == 100)
            Text.msg(player, "<green>Double drop chance is now <white>" + Math.round(doubleDropChance(p, s) * 100) + "%");
        if (level >= maxLevel() && cfg().getBoolean("perks.title-at-100", true)) {
            String title = "<gold>" + s.display + " Master";
            if (p.titles.add(title)) Text.msg(player, "<gold>New title unlocked: " + title);
        }
        plugin.path.check(player);
        plugin.islandMissions.refreshOwner(player.getUniqueId());
    }
}
