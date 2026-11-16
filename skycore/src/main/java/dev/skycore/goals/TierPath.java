package dev.skycore.goals;

import dev.skycore.SkyCore;
import dev.skycore.data.Island;
import dev.skycore.data.Profile;
import dev.skycore.gen.GeneratorService;
import dev.skycore.skills.Skill;
import dev.skycore.util.Text;
import dev.skycore.world.Dim;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.*;

/** Path of Tiers: 10 account tiers with a checklist. Tiers never gate mechanics; they are visible goals + rewards. */
public final class TierPath {
    public record Tier(int number, String name, int days, List<String> requirements, List<String> rewards) {}

    private final SkyCore plugin;
    public final TreeMap<Integer, Tier> tiers = new TreeMap<>();

    public TierPath(SkyCore plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        tiers.clear();
        ConfigurationSection s = plugin.settings.goals.getConfigurationSection("tiers");
        for (String k : s.getKeys(false)) {
            ConfigurationSection t = s.getConfigurationSection(k);
            int n = Integer.parseInt(k);
            tiers.put(n, new Tier(n, t.getString("name"), t.getInt("days"), t.getStringList("requirements"), t.getStringList("rewards")));
        }
    }

    public Tier tier(int n) {
        return tiers.get(n);
    }

    public String name(int n) {
        Tier t = tiers.get(n);
        return t == null ? "?" : t.name();
    }

    /** {current, target} for a requirement. */
    public double[] state(UUID u, String req) {
        Profile p = plugin.data.profile(u);
        String[] a = req.split(":");
        List<Island> owned = plugin.islands.owned(u);
        return switch (a[0]) {
            case "island_level" -> new double[]{plugin.islands.highestLevel(u), num(a[1])};
            case "skill" -> new double[]{plugin.skills.level(p, Skill.parse(a[1])), num(a[2])};
            case "skills_all" -> {
                int min = Integer.MAX_VALUE;
                for (Skill s : Skill.values()) min = Math.min(min, plugin.skills.level(p, s));
                yield new double[]{min, num(a[1])};
            }
            case "visit_nether" -> new double[]{p.visitedNether ? 1 : 0, 1};
            case "minions" -> new double[]{owned.stream().mapToInt(i -> i.minions.size()).sum(), num(a[1])};
            case "minions_tier" -> {
                int t = Integer.parseInt(a[1]);
                long c = owned.stream().flatMap(i -> i.minions.values().stream()).filter(m -> m.tier >= t).count();
                yield new double[]{c, num(a[2])};
            }
            case "islands" -> new double[]{owned.size(), num(a[1])};
            case "total_sold" -> new double[]{p.totalSold, num(a[1])};
            case "end_portal" -> new double[]{owned.stream().anyMatch(i -> i.endPortal) ? 1 : 0, 1};
            case "spawner_stack" -> new double[]{owned.stream().mapToInt(i -> plugin.spawners.maxStackOnIsland(i, "any")).max().orElse(0), num(a[1])};
            case "generators_max" -> new double[]{owned.stream().anyMatch(this::gensMax) ? 1 : 0, 1};
            default -> new double[]{0, 1};
        };
    }

    private static double num(String s) {
        return Double.parseDouble(s);
    }

    private boolean gensMax(Island i) {
        var g = plugin.settings.gens;
        return GeneratorService.level(i, Dim.OVERWORLD) >= g.get(Dim.OVERWORLD).lastKey()
                && GeneratorService.level(i, Dim.NETHER) >= g.get(Dim.NETHER).lastKey()
                && GeneratorService.level(i, Dim.END) >= g.get(Dim.END).lastKey();
    }

    public String describe(String req) {
        String[] a = req.split(":");
        return switch (a[0]) {
            case "island_level" -> "Island level " + a[1];
            case "skill" -> Text.pretty(a[1]) + " " + a[2];
            case "skills_all" -> "All skills " + a[1];
            case "visit_nether" -> "Visit the Nether";
            case "minions" -> a[1] + " minions";
            case "minions_tier" -> a[2] + " minions tier " + Text.roman(Integer.parseInt(a[1])) + "+";
            case "islands" -> "Own " + a[1] + " islands";
            case "total_sold" -> "Sell " + Text.compact(num(a[1])) + " Dollars in total";
            case "end_portal" -> "Activate an End portal";
            case "spawner_stack" -> "A spawner stack of " + a[1];
            case "generators_max" -> "All generators maxed";
            default -> req;
        };
    }

    public boolean done(UUID u, String req) {
        double[] s = state(u, req);
        return s[0] >= s[1];
    }

    /** First missing requirement of the next tier, or null. */
    public String nextMissing(UUID u) {
        Profile p = plugin.data.profile(u);
        Tier next = tiers.get(p.tier + 1);
        if (next == null) return null;
        for (String r : next.requirements()) if (!done(u, r)) return r;
        return null;
    }

    public void check(Player player) {
        Profile p = plugin.data.profile(player.getUniqueId());
        if (p == null) return;
        while (true) {
            Tier next = tiers.get(p.tier + 1);
            if (next == null) return;
            for (String r : next.requirements()) if (!done(p.uuid, r)) return;
            p.tier = next.number();
            plugin.data.dirty(p);
            List<String> given = new ArrayList<>();
            for (String r : next.rewards()) {
                plugin.rewards.grant(p.uuid, r, "tier");
                given.add(plugin.rewards.describe(r));
            }
            Bukkit.broadcast(Text.mm(Text.PREFIX + "<white>" + player.getName() + "</white> reached Tier " + next.number()
                    + " <gold>" + next.name() + "</gold>!"));
            Text.msg(player, "<gray>Rewards: <white>" + String.join("<gray>, <white>", given));
        }
    }

    public void tick() {
        for (Player p : Bukkit.getOnlinePlayers()) check(p);
    }
}
