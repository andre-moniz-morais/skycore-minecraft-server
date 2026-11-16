package dev.skycore.island;

import dev.skycore.SkyCore;
import dev.skycore.config.Settings;
import dev.skycore.data.Island;
import dev.skycore.data.Profile;
import dev.skycore.gen.GeneratorService;
import dev.skycore.goals.IslandMissions;
import dev.skycore.gui.Menu;
import dev.skycore.util.Text;
import dev.skycore.world.Dim;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Island GUI: overview, island list, upgrades, members and island missions. */
public final class IslandMenus {
    private final SkyCore plugin;

    public IslandMenus(SkyCore plugin) {
        this.plugin = plugin;
    }

    /** The island the player is standing on if they are a member, else their main island. */
    public Island context(Player p) {
        Island here = plugin.islands.at(p.getLocation());
        if (here != null && here.isMember(p.getUniqueId())) return here;
        Island main = plugin.islands.main(p.getUniqueId());
        if (main != null) return main;
        List<Island> acc = plugin.islands.accessible(p.getUniqueId());
        return acc.isEmpty() ? null : acc.getFirst();
    }

    public void main(Player p, Island i) {
        Settings.IslandLevel lvl = plugin.settings.islandLevel(i.level);
        Menu m = new Menu(5, "<dark_gray>" + Text.escape(plugin.islands.label(i)));
        m.set(4, Menu.icon(Material.GRASS_BLOCK, "<green>" + Text.escape(plugin.islands.label(i)), List.of(
                "Level: <white>" + i.level + "<gray>/" + plugin.settings.maxIslandLevel() + "  Size: <white>" + lvl.size() + "x" + lvl.size(),
                "Value: <white>" + Text.compact(plugin.islands.value(i)),
                "Sell multiplier: <white>x" + Text.num(plugin.pricing.islandMultiplier(i)),
                "Generators: <white>O" + i.genOverworld + " N" + i.genNether + " E" + i.genEnd,
                "Members: <white>" + (i.members.size() + 1) + "<gray>/" + (plugin.settings.maxMembers + 1))));
        m.set(19, Menu.icon(Material.ENDER_PEARL, "<aqua>Teleport home", List.of("Go to this island.")), e -> {
            p.closeInventory();
            plugin.islands.teleport(p, plugin.islands.home(i));
        });
        m.set(20, Menu.icon(Material.EXPERIENCE_BOTTLE, "<yellow>Upgrades", List.of("Level, sell multiplier, crops, generators.")), e -> upgrades(p, i));
        m.set(21, Menu.icon(Material.WRITABLE_BOOK, "<gold>Island missions", List.of(missionsDone(i) + "/" + plugin.islandMissions.defs.size() + " completed")), e -> missions(p, i));
        m.set(22, Menu.icon(Material.PLAYER_HEAD, "<white>Members", List.of("Invite: /is invite <player>")), e -> members(p, i));
        m.set(23, Menu.icon(Material.COMPASS, "<white>Your islands", List.of("Switch between islands.")), e -> list(p));
        m.set(24, Menu.icon(Material.HOPPER, "<white>Limits", limits(i)), null);
        m.set(25, Menu.icon(Material.EMERALD, "<green>Shop", List.of("Open /shop")), e -> plugin.shopMenus.main(p));
        m.set(40, Menu.icon(Material.NETHER_STAR, "<light_purple>Path of Tiers", List.of("Your account career: /path")), e -> plugin.goalMenus.path(p));
        m.fill(Material.BLACK_STAINED_GLASS_PANE);
        m.open(p);
    }

    private long missionsDone(Island i) {
        return plugin.islandMissions.defs.stream().filter(d -> i.missionsDone.contains(d.id())).count();
    }

    private List<String> limits(Island i) {
        Settings.IslandLevel l = plugin.settings.islandLevel(i.level);
        return List.of(
                "Minions: <white>" + i.minions.size() + "/" + l.minions(),
                "Hoppers: <white>" + i.hoppers + "/" + l.hoppers(),
                "Chunk hoppers: <white>" + i.chunkHoppers.size() + "/" + l.chunkHoppers(),
                "Sell chests: <white>" + i.sellChests.size() + "/" + l.sellChests(),
                "Shops: <white>" + i.shops.size() + "/" + l.shops(),
                "Spawner units: <white>" + plugin.spawners.totalUnits(i) + "/" + l.spawnersTotal(),
                "Max spawner stack: <white>" + plugin.spawners.maxStack(i, Dim.OVERWORLD) + " <gray>(Nether "
                        + plugin.spawners.maxStack(i, Dim.NETHER) + ", End " + plugin.spawners.maxStack(i, Dim.END) + ")");
    }

    public void list(Player p) {
        Menu m = new Menu(3, "<dark_gray>Your islands");
        int slot = 10;
        for (Island i : plugin.islands.accessible(p.getUniqueId())) {
            boolean own = i.owner.equals(p.getUniqueId());
            m.set(slot++, Menu.icon(own ? Material.GRASS_BLOCK : Material.MOSS_BLOCK, "<green>" + Text.escape(plugin.islands.label(i)), List.of(
                    own ? "<gold>Owner" : "<gray>Member", "Level " + i.level + ", value " + Text.compact(plugin.islands.value(i)),
                    "<yellow>Left</yellow>: teleport  <yellow>Right</yellow>: manage")), e -> {
                if (e.getClick().isRightClick()) main(p, i);
                else {
                    p.closeInventory();
                    plugin.islands.teleport(p, plugin.islands.home(i));
                }
            });
        }
        int owned = plugin.islands.owned(p.getUniqueId()).size();
        Settings.ExtraIsland ex = plugin.settings.extraIslands.get(owned + 1);
        if (owned == 0) {
            m.set(22, Menu.icon(Material.OAK_SAPLING, "<green>Create your island", List.of("Free.")), e -> {
                p.closeInventory();
                plugin.islands.buyExtra(p);
            });
        } else if (ex != null) {
            m.set(22, Menu.icon(Material.OAK_SAPLING, "<green>Buy island #" + ex.number(), List.of(
                    "Requires main island level " + ex.mainLevel(),
                    "Cost: " + Text.roses(ex.roses()) + " <gray>+ " + Text.dollars(ex.dollars()),
                    "Each island progresses independently.", "<green>Click to buy")), e -> {
                p.closeInventory();
                plugin.islands.buyExtra(p);
            });
        }
        m.open(p);
    }

    public void upgrades(Player p, Island i) {
        Menu m = new Menu(4, "<dark_gray>Upgrades - " + Text.escape(plugin.islands.label(i)));
        Map<UpgradeService.Kind, Material> icons = Map.of(
                UpgradeService.Kind.LEVEL, Material.GRASS_BLOCK, UpgradeService.Kind.SELL, Material.GOLD_INGOT,
                UpgradeService.Kind.GROWTH, Material.WHEAT, UpgradeService.Kind.GEN_OVERWORLD, Material.COBBLESTONE,
                UpgradeService.Kind.GEN_NETHER, Material.NETHERRACK, UpgradeService.Kind.GEN_END, Material.END_STONE);
        int[] slots = {10, 12, 14, 19, 21, 23};
        int k = 0;
        for (UpgradeService.Kind kind : UpgradeService.Kind.values()) {
            UpgradeService.Offer o = plugin.upgrades.offer(i, kind);
            List<String> lore = new ArrayList<>();
            lore.add("Current: <white>" + currentText(i, kind));
            if (o.maxed()) lore.add("<green>Maxed");
            else {
                lore.add("Next: <white>" + nextText(i, kind, o.next()));
                lore.add("");
                lore.add("Requires value: <white>" + Text.compact(o.cost().value()));
                if (o.islandLevelReq() > 0) lore.add("Requires island level: <white>" + o.islandLevelReq());
                lore.add("Cost: " + Text.roses(o.cost().roses()) + " <gray>+ " + Text.dollars(o.cost().dollars()));
                lore.add("");
                if (o.missing().isEmpty()) lore.add("<green>Click to upgrade");
                else for (String s : o.missing()) lore.add("<red>Missing: " + s);
            }
            m.set(slots[k++], Menu.icon(icons.get(kind), "<yellow>" + kind.display, lore, o.missing().isEmpty() && !o.maxed()), e -> {
                plugin.upgrades.buy(p, i, kind);
                upgrades(p, i);
            });
        }
        m.set(16, Menu.icon(Material.BOOK, "<white>Generator odds", genOdds(i)), null);
        m.set(31, Menu.back(), e -> main(p, i));
        m.fill(Material.GRAY_STAINED_GLASS_PANE);
        m.open(p);
    }

    private List<String> genOdds(Island i) {
        List<String> out = new ArrayList<>();
        for (Dim d : Dim.values()) {
            Settings.GenLevel g = plugin.settings.gens.get(d).get(GeneratorService.level(i, d));
            out.add("<gold>" + d.display + (g == null ? " <dark_gray>(not bought)" : " <gray>(level " + g.level() + ")"));
            if (g == null) continue;
            StringBuilder sb = new StringBuilder();
            g.blocks().forEach((mat, w) -> sb.append(Text.pretty(mat.name().replace("_ORE", "").replace("NETHER_", ""))).append(" ").append(Text.num(w)).append("%, "));
            if (g.frameChance() > 0) sb.append("End frame ").append(Text.num(g.frameChance() * 100)).append("%");
            out.add("<white>" + sb);
        }
        return out;
    }

    private String currentText(Island i, UpgradeService.Kind k) {
        return switch (k) {
            case LEVEL -> "Level " + i.level + " (" + plugin.settings.islandLevel(i.level).size() + "x" + plugin.settings.islandLevel(i.level).size() + ")";
            case SELL -> "x" + Text.num(plugin.pricing.islandMultiplier(i));
            case GROWTH -> Text.num(plugin.settings.cropGrowth.get(i.growthLevel).amount()) + "s regrow";
            default -> {
                int l = plugin.upgrades.current(i, k);
                yield l == 0 ? "Not bought" : "Level " + l;
            }
        };
    }

    private String nextText(Island i, UpgradeService.Kind k, int next) {
        return switch (k) {
            case LEVEL -> "Level " + next + " (" + plugin.settings.islandLevel(next).size() + "x" + plugin.settings.islandLevel(next).size() + ")";
            case SELL -> "x" + Text.num(plugin.settings.sellMultiplier.get(next).amount());
            case GROWTH -> Text.num(plugin.settings.cropGrowth.get(next).amount()) + "s regrow";
            default -> "Level " + next;
        };
    }

    public void members(Player p, Island i) {
        Menu m = new Menu(3, "<dark_gray>Members");
        List<UUID> all = new ArrayList<>();
        all.add(i.owner);
        all.addAll(i.members);
        int slot = 10;
        for (UUID u : all) {
            Profile prof = plugin.data.profile(u);
            boolean owner = u.equals(i.owner);
            m.set(slot++, Menu.icon(Material.PLAYER_HEAD, "<white>" + (prof == null ? "?" : Text.escape(prof.name)), List.of(
                    owner ? "<gold>Owner" : "<gray>Member",
                    !owner && i.owner.equals(p.getUniqueId()) ? "<red>Click to remove" : "")), e -> {
                if (!owner && i.owner.equals(p.getUniqueId()) && prof != null) {
                    plugin.islands.kick(p, i, prof.name);
                    members(p, i);
                }
            });
        }
        m.set(22, Menu.back(), e -> main(p, i));
        m.open(p);
    }

    public void missions(Player p, Island i) {
        Menu m = new Menu(4, "<dark_gray>Island missions");
        int slot = 0;
        for (IslandMissions.Def d : plugin.islandMissions.defs) {
            boolean done = i.missionsDone.contains(d.id());
            boolean active = i.level >= d.level();
            long[] st = plugin.islandMissions.state(i, d);
            List<String> lore = new ArrayList<>();
            lore.add("Island level " + d.level());
            lore.add(done ? "<green>Completed" : active ? Text.progressBar((double) st[0] / st[1], 20) + " <white>" + Text.compact(st[0]) + "/" + Text.compact(st[1]) : "<red>Locked");
            lore.add("Rewards:");
            for (String r : d.rewards()) lore.add(" <white>" + plugin.rewards.describe(r));
            m.set(slot++, Menu.icon(done ? Material.LIME_DYE : active ? Material.YELLOW_DYE : Material.GRAY_DYE,
                    (done ? "<green>" : "<yellow>") + d.name(), lore), null);
        }
        m.set(31, Menu.back(), e -> main(p, i));
        m.open(p);
    }
}
