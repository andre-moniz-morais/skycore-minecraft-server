package dev.skycore.goals;

import dev.skycore.SkyCore;
import dev.skycore.data.Profile;
import dev.skycore.skills.Skill;
import dev.skycore.util.Text;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Three daily missions (easy, medium, hard) generated at 00:00 (server timezone) from templates.
 * Target = base x (1 + skill level / 50). Only active skill actions count. Rewards are Dollars by the
 * highest island level plus one item; a 7-day streak gives a chunk hopper.
 */
public final class DailyMissions {
    private static final String[] DIFFS = {"easy", "medium", "hard"};
    private final SkyCore plugin;

    public DailyMissions(SkyCore plugin) {
        this.plugin = plugin;
    }

    private String today() {
        return LocalDate.now(plugin.settings.zone).toString();
    }

    public void ensure(Profile p) {
        String today = today();
        if (today.equals(p.daily.date) && !p.daily.missions.isEmpty()) return;
        p.daily.date = today;
        p.daily.missions.clear();
        Random r = new Random((today + p.uuid).hashCode());
        double div = plugin.settings.goals.getDouble("daily.level-divisor", 50);
        for (String d : DIFFS) {
            List<Map<?, ?>> templates = plugin.settings.goals.getMapList("daily.templates." + d);
            if (templates.isEmpty()) continue;
            Map<?, ?> t = templates.get(r.nextInt(templates.size()));
            Skill s = Skill.parse(String.valueOf(t.get("skill")));
            long base = ((Number) t.get("base")).longValue();
            Profile.DailyMission m = new Profile.DailyMission();
            m.difficulty = d;
            m.skill = s.name();
            m.target = Math.max(1, Math.round(base * (1 + plugin.skills.level(p, s) / div)));
            m.text = String.valueOf(t.get("text")).replace("{n}", Text.num(m.target));
            p.daily.missions.add(m);
        }
        plugin.data.dirty(p);
    }

    public void progress(Player player, Profile p, Skill s, long n) {
        ensure(p);
        boolean completedAny = false;
        for (Profile.DailyMission m : p.daily.missions) {
            if (m.done || !m.skill.equals(s.name())) continue;
            m.progress = Math.min(m.target, m.progress + n);
            if (m.progress >= m.target) {
                m.done = true;
                completedAny = true;
                reward(player, p, m);
            }
        }
        if (completedAny && p.daily.missions.stream().allMatch(m -> m.done)) {
            String today = today();
            String yesterday = LocalDate.now(plugin.settings.zone).minusDays(1).toString();
            if (!today.equals(p.daily.lastFullDate)) {
                p.daily.streak = yesterday.equals(p.daily.lastFullDate) ? p.daily.streak + 1 : 1;
                p.daily.lastFullDate = today;
                int days = plugin.settings.goals.getInt("daily.streak-days", 7);
                Text.msg(player, "<gold>All daily missions done! <gray>Streak: <white>" + p.daily.streak + "<gray>/" + days);
                if (p.daily.streak % days == 0) {
                    String reward = plugin.settings.goals.getString("daily.streak-reward", "chunk_hopper:1");
                    plugin.rewards.grant(p.uuid, reward, "daily_streak");
                    Text.msg(player, "<gold>" + days + "-day streak reward: <white>" + plugin.rewards.describe(reward));
                }
            }
        }
    }

    public double dollarReward(Profile p, String difficulty) {
        int lvl = plugin.islands.highestLevel(p.uuid);
        for (Map<?, ?> row : plugin.settings.goals.getMapList("daily.rewards")) {
            int max = ((Number) row.get("max-level")).intValue();
            if (lvl <= max) return ((Number) row.get(difficulty)).doubleValue();
        }
        return 0;
    }

    private void reward(Player player, Profile p, Profile.DailyMission m) {
        double dollars = dollarReward(p, m.difficulty);
        plugin.eco.deposit(p.uuid, dollars, "daily_mission");
        List<String> items = plugin.settings.goals.getStringList("daily.item-rewards");
        String item = items.isEmpty() ? null : items.get(new Random().nextInt(items.size()));
        if (item != null) plugin.rewards.grant(p.uuid, item, "daily_mission");
        Text.msg(player, "<green>Daily mission complete: <white>" + m.text + "</white> <gray>-> " + Text.dollars(dollars)
                + (item != null ? " <gray>+ <white>" + plugin.rewards.describe(item) : ""));
    }

    public ConfigurationSection cfg() {
        return plugin.settings.goals;
    }
}
