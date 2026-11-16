package dev.skycore.goals;

import dev.skycore.SkyCore;
import dev.skycore.data.Island;
import dev.skycore.data.Profile;
import dev.skycore.gen.GeneratorService;
import dev.skycore.skills.Skill;
import dev.skycore.util.Text;
import dev.skycore.world.Dim;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.*;

/**
 * Chain of island missions (2 per island level). Active once the island reaches the level. Item rewards
 * are paid once per account; extra islands only get the Dollars.
 */
public final class IslandMissions {
    public record Def(String id, int level, String name, String objective, List<String> rewards) {
        String counterKey() {
            String[] p = objective.split(":");
            return p[0].equals("break") ? p[0] + ":" + p[1] : p[0];
        }

        long target() {
            String[] p = objective.split(":");
            try {
                return Long.parseLong(p[p.length - 1]);
            } catch (NumberFormatException e) {
                return 1;
            }
        }
    }

    private static final Set<String> COUNTERS = Set.of("harvest", "gen_blocks", "end_gen_blocks", "break", "sellchest_dollars", "shop_sales");
    private final SkyCore plugin;
    public final List<Def> defs = new ArrayList<>();

    public IslandMissions(SkyCore plugin) {
        this.plugin = plugin;
        reload();
    }

    @SuppressWarnings("unchecked")
    public void reload() {
        defs.clear();
        for (Map<?, ?> m : plugin.settings.goals.getMapList("island-missions")) {
            defs.add(new Def(String.valueOf(m.get("id")), ((Number) m.get("level")).intValue(), String.valueOf(m.get("name")),
                    String.valueOf(m.get("objective")), (List<String>) m.get("rewards")));
        }
    }

    private boolean active(Island i, Def d) {
        return i.level >= d.level && !i.missionsDone.contains(d.id);
    }

    public void progress(Island i, String key, long n) {
        if (n <= 0) return;
        for (Def d : defs) {
            if (!active(i, d) || !COUNTERS.contains(d.objective.split(":")[0]) || !d.counterKey().equals(key)) continue;
            long v = i.missionProgress.merge(d.id, n, Long::sum);
            plugin.data.dirty(i);
            if (v >= d.target()) complete(i, d);
        }
    }

    /** Current progress and target of a mission, for menus and checks. */
    public long[] state(Island i, Def d) {
        String[] p = d.objective.split(":");
        Profile owner = plugin.data.profile(i.owner);
        long target = d.target();
        if (COUNTERS.contains(p[0])) return new long[]{i.missionProgress.getOrDefault(d.id, 0L), target};
        return switch (p[0]) {
            case "minions" -> new long[]{i.minions.size(), target};
            case "minions_tier" -> {
                int t = Integer.parseInt(p[1]);
                yield new long[]{i.minions.values().stream().filter(m -> m.tier >= t).count(), target};
            }
            case "visit_nether" -> new long[]{i.netherVisited ? 1 : 0, 1};
            case "end_portal" -> new long[]{i.endPortal ? 1 : 0, 1};
            case "spawner_stack" -> new long[]{plugin.spawners.maxStackOnIsland(i, p[1]), target};
            case "islands" -> new long[]{plugin.islands.owned(i.owner).size(), target};
            case "skill" -> new long[]{owner == null ? 0 : plugin.skills.level(owner, Skill.parse(p[1])), target};
            case "skills_all" -> {
                long min = Long.MAX_VALUE;
                for (Skill s : Skill.values()) min = Math.min(min, owner == null ? 0 : plugin.skills.level(owner, s));
                yield new long[]{min, target};
            }
            case "all_upgrades_max" -> new long[]{allMax(i) ? 1 : 0, 1};
            default -> new long[]{0, target};
        };
    }

    public boolean allMax(Island i) {
        var s = plugin.settings;
        return i.level >= s.maxIslandLevel() && i.sellLevel >= s.sellMultiplier.lastKey() && i.growthLevel >= s.cropGrowth.lastKey()
                && GeneratorService.level(i, Dim.OVERWORLD) >= s.gens.get(Dim.OVERWORLD).lastKey()
                && GeneratorService.level(i, Dim.NETHER) >= s.gens.get(Dim.NETHER).lastKey()
                && GeneratorService.level(i, Dim.END) >= s.gens.get(Dim.END).lastKey();
    }

    public void check(Island i) {
        if (i == null || i.retired) return;
        for (Def d : defs) {
            if (!active(i, d)) continue;
            long[] st = state(i, d);
            if (st[0] >= st[1]) complete(i, d);
        }
    }

    public void refreshOwner(UUID u) {
        for (Island i : plugin.islands.owned(u)) check(i);
    }

    private void complete(Island i, Def d) {
        if (!i.missionsDone.add(d.id)) return;
        plugin.data.dirty(i);
        Profile owner = plugin.data.profile(i.owner);
        boolean itemsAllowed = owner != null && !owner.onceRewards.contains("island:" + d.id);
        List<String> given = new ArrayList<>();
        for (String r : d.rewards) {
            if (RewardService.isItemReward(r) && !itemsAllowed) continue;
            plugin.rewards.grant(i.owner, r, "island_mission");
            given.add(plugin.rewards.describe(r));
        }
        if (owner != null) {
            owner.onceRewards.add("island:" + d.id);
            plugin.data.dirty(owner);
        }
        for (UUID u : members(i)) {
            Player p = Bukkit.getPlayer(u);
            if (p != null) Text.msg(p, "<gold>Island mission complete: <white>" + d.name + "</white> <gray>-> " + String.join("<gray>, ", given));
        }
    }

    private List<UUID> members(Island i) {
        List<UUID> l = new ArrayList<>(i.members);
        l.add(i.owner);
        return l;
    }

    public void tick() {
        for (Island i : plugin.islands.active()) check(i);
    }
}
