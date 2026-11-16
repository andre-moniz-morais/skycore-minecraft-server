package dev.skycore.goals;

import dev.skycore.SkyCore;
import dev.skycore.data.Island;
import dev.skycore.data.Profile;
import dev.skycore.gui.Menu;
import dev.skycore.skills.Skill;
import dev.skycore.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

/** /skills, /missions and /path menus. */
public final class GoalMenus {
    private final SkyCore plugin;

    public GoalMenus(SkyCore plugin) {
        this.plugin = plugin;
    }

    public void skills(Player p) {
        Profile prof = plugin.data.profile(p.getUniqueId());
        Menu m = new Menu(3, "<dark_gray>Skills");
        int slot = 10;
        for (Skill s : Skill.values()) {
            int lvl = plugin.skills.level(prof, s);
            Profile.SkillData d = prof.skill(s);
            int cap = plugin.settings.skills.getInt("daily-cap.payouts", 120);
            List<String> lore = new ArrayList<>();
            lore.add("Level: <white>" + lvl + "<gray>/" + plugin.skills.maxLevel());
            lore.add(Text.progressBar(plugin.skills.progress(prof, s), 25) + " <gray>" + Math.round(plugin.skills.progress(prof, s) * 100) + "%");
            lore.add("XP: <white>" + Text.compact(d.xp) + " <gray>(next level at " + Text.compact(plugin.skills.xpForLevel(Math.min(lvl + 1, plugin.skills.maxLevel()))) + ")");
            lore.add("");
            lore.add("Roses: <white>" + plugin.skills.rosesPerPayout(lvl) + " ✿ <gray>every " + plugin.skills.payoutEvery(s) + " actions (" + d.actions + "/" + plugin.skills.payoutEvery(s) + ")");
            lore.add("Payouts today: <white>" + (plugin.skills.level(prof, s) > 0 && LocalDate.now(plugin.settings.zone).toString().equals(prof.capDay) ? d.payoutsToday : 0)
                    + "<gray>/" + cap + " at full rate");
            lore.add("Sell bonus: <white>+" + Text.num(Math.round(plugin.skills.sellBonus(prof, s) * 1000) / 10.0) + "%");
            lore.add("Double drops: <white>" + Math.round(plugin.skills.doubleDropChance(prof, s) * 100) + "%");
            lore.add("Level 10 perk: " + (plugin.skills.hasUtilityPerk(prof, s) ? "<green>" : "<red>") + switch (s) {
                case MINING -> "Haste I on your islands";
                case FARMING -> "auto-replant normal crops";
                case FISHING -> "20% shorter fishing wait";
                case COMBAT -> "mob drop magnet";
            });
            m.set(slot, Menu.icon(s.icon, "<aqua>" + s.display + " <white>" + lvl, lore), null);
            slot += 2;
        }
        m.fill(Material.GRAY_STAINED_GLASS_PANE);
        m.open(p);
    }

    public void missions(Player p) {
        Profile prof = plugin.data.profile(p.getUniqueId());
        plugin.daily.ensure(prof);
        Menu m = new Menu(3, "<dark_gray>Missions");
        int slot = 10;
        for (Profile.DailyMission d : prof.daily.missions) {
            Material icon = switch (d.difficulty) {
                case "easy" -> Material.LIME_CONCRETE;
                case "medium" -> Material.YELLOW_CONCRETE;
                default -> Material.RED_CONCRETE;
            };
            m.set(slot, Menu.icon(d.done ? Material.LIME_DYE : icon, (d.done ? "<green>" : "<yellow>") + Text.pretty(d.difficulty) + ": <white>" + d.text, List.of(
                    Text.progressBar((double) d.progress / d.target, 20) + " <white>" + Text.num(d.progress) + "/" + Text.num(d.target),
                    "Reward: " + Text.dollars(plugin.daily.dollarReward(prof, d.difficulty)) + " <gray>+ 1 item",
                    d.done ? "<green>Completed" : "<gray>Only active " + Text.pretty(d.skill) + " actions count.")), null);
            slot += 2;
        }
        ZonedDateTime now = ZonedDateTime.now(plugin.settings.zone);
        long secs = Duration.between(now, now.toLocalDate().plusDays(1).atStartOfDay(plugin.settings.zone)).getSeconds();
        m.set(4, Menu.icon(Material.CLOCK, "<white>Daily missions", List.of("New missions in <white>" + Text.duration(secs),
                "Streak: <white>" + prof.daily.streak + "<gray>/" + plugin.settings.goals.getInt("daily.streak-days", 7) + " days")), null);
        Island i = plugin.islandMenus.context(p);
        if (i != null) m.set(16, Menu.icon(Material.WRITABLE_BOOK, "<gold>Island missions", List.of("For " + Text.escape(plugin.islands.label(i)))),
                e -> plugin.islandMenus.missions(p, i));
        m.set(22, Menu.icon(Material.NETHER_STAR, "<light_purple>Path of Tiers", List.of("Open /path")), e -> path(p));
        m.fill(Material.GRAY_STAINED_GLASS_PANE);
        m.open(p);
    }

    public void path(Player p) {
        Profile prof = plugin.data.profile(p.getUniqueId());
        Menu m = new Menu(6, "<dark_gray>Path of Tiers <gray>(Tier " + prof.tier + ")");
        int slot = 0;
        for (TierPath.Tier t : plugin.path.tiers.values()) {
            boolean done = prof.tier >= t.number();
            boolean next = prof.tier + 1 == t.number();
            List<String> lore = new ArrayList<>();
            lore.add("Target: ~" + t.days() + " days");
            for (String r : t.requirements()) {
                double[] st = plugin.path.state(p.getUniqueId(), r);
                boolean ok = done || st[0] >= st[1];
                lore.add((ok ? "<green>✔ " : "<red>✘ ") + "<white>" + plugin.path.describe(r)
                        + (ok || st[1] <= 1 ? "" : " <gray>(" + Text.compact(st[0]) + "/" + Text.compact(st[1]) + ")"));
            }
            lore.add("Rewards:");
            for (String r : t.rewards()) lore.add(" <white>" + plugin.rewards.describe(r));
            Material icon = done ? Material.LIME_STAINED_GLASS : next ? Material.YELLOW_STAINED_GLASS : Material.GRAY_STAINED_GLASS;
            m.set(slot, Menu.icon(icon, (done ? "<green>" : next ? "<yellow>" : "<gray>") + "Tier " + t.number() + " - " + t.name(), lore, next), null);
            slot += slot % 9 == 7 ? 3 : 2;
        }
        int maxTier = plugin.path.tiers.lastKey();
        m.set(49, Menu.icon(Material.NETHER_STAR, "<gold>Ascension <yellow>★" + prof.stars, List.of(
                "Unlocks at Tier " + maxTier + ".", "Resets islands and Dollars, keeps skills, Roses, pets.",
                "+3% sell, +5% XP per star.", "<gray>/prestige")), null);
        m.open(p);
    }
}
