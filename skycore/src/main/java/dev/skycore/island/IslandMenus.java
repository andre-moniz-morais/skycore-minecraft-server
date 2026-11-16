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
            if (kind == UpgradeService.Kind.GROWTH) lore.add("<gray>Speeds up special and normal crops.");
            Dim genDim = genDim(kind);
            if (o.maxed()) {
                lore.add("<green>Maxed");
                if (genDim != null) {
                    lore.add("");
                    lore.addAll(oddsLines(genLevel(genDim, o.current()), null));
                }
            } else {
                lore.add("Next: <white>" + nextText(i, kind, o.next()));
                if (genDim != null) {
                    lore.add("");
                    lore.add("<gray>Block odds <dark_gray>(now -> next)");
                    lore.addAll(oddsLines(genLevel(genDim, o.current()), genLevel(genDim, o.next())));
                }
                lore.add("");
                lore.add("Requires value: <white>" + Text.compact(o.cost().value()));
                if (o.islandLevelReq() > 0) lore.add("Requires island level: <white>" + o.islandLevelReq());
                lore.add("Cost: " + Text.roses(o.cost().roses()) + " <gray>+ " + Text.dollars(o.cost().dollars()));
                lore.add("");
                if (o.missing().isEmpty()) lore.add("<green>Click to upgrade");
                else for (String s : o.missing()) lore.add("<red>Missing: " + s);
            }
            if (genDim != null) lore.add("<yellow>Right-click</yellow> <gray>to see every level");
            m.set(slots[k++], Menu.icon(icons.get(kind), "<yellow>" + kind.display, lore, o.missing().isEmpty() && !o.maxed()), e -> {
                if (genDim != null && e.getClick().isRightClick()) {
                    generatorLevels(p, i);
                    return;
                }
                plugin.upgrades.buy(p, i, kind);
                upgrades(p, i);
            });
        }
        m.set(16, Menu.icon(Material.BOOK, "<white>Generator levels", List.of("Block odds of every generator", "level in every dimension.", "", "<green>Click to open")),
                e -> generatorLevels(p, i));
        m.set(31, Menu.back(), e -> main(p, i));
        m.fill(Material.GRAY_STAINED_GLASS_PANE);
        m.open(p);
    }

    private static Dim genDim(UpgradeService.Kind k) {
        return switch (k) {
            case GEN_OVERWORLD -> Dim.OVERWORLD;
            case GEN_NETHER -> Dim.NETHER;
            case GEN_END -> Dim.END;
            default -> null;
        };
    }

    private Settings.GenLevel genLevel(Dim d, int level) {
        return plugin.settings.gens.get(d).get(level);
    }

    /** Each block's chance in percent (weights normalised, so tables need not sum to exactly 100). */
    private static Map<Material, Double> percents(Settings.GenLevel g) {
        Map<Material, Double> out = new java.util.LinkedHashMap<>();
        if (g == null) return out;
        double total = g.blocks().values().stream().mapToDouble(Double::doubleValue).filter(w -> w > 0).sum();
        if (total <= 0) return out;
        g.blocks().forEach((mat, w) -> {
            if (w > 0) out.put(mat, w * 100 / total);
        });
        return out;
    }

    private static String pct(double v) {
        // small chances (End Portal Frames: 0.03%) need more decimals than ore odds
        return Text.num(v >= 1 ? Math.round(v * 10) / 10.0 : Math.round(v * 100) / 100.0) + "%";
    }

    /** One lore line per block: "Diamond Ore 1% -> 4%" (next may be null to show a single level). */
    private static List<String> oddsLines(Settings.GenLevel cur, Settings.GenLevel next) {
        Map<Material, Double> now = percents(cur), after = percents(next);
        java.util.LinkedHashSet<Material> mats = new java.util.LinkedHashSet<>(now.keySet());
        mats.addAll(after.keySet());
        List<String> out = new ArrayList<>();
        for (Material mat : mats) {
            double a = now.getOrDefault(mat, 0d);
            StringBuilder sb = new StringBuilder(" <white>").append(Text.pretty(mat.name())).append(" <gray>").append(a > 0 ? pct(a) : "-");
            if (next != null) {
                double b = after.getOrDefault(mat, 0d);
                String color = b > a + 1e-9 ? "<green>" : b < a - 1e-9 ? "<red>" : "<gray>";
                sb.append(" <dark_gray>-> ").append(color).append(b > 0 ? pct(b) : "-");
                if (a == 0 && b > 0) sb.append(" <gold>NEW");
            }
            out.add(sb.toString());
        }
        double f0 = cur == null ? 0 : cur.frameChance(), f1 = next == null ? f0 : next.frameChance();
        if (f0 > 0 || f1 > 0) {
            String line = " <light_purple>End Portal Frame <gray>" + (f0 > 0 ? pct(f0 * 100) : "-");
            if (next != null) line += " <dark_gray>-> " + (f1 > f0 ? "<green>" : "<gray>") + (f1 > 0 ? pct(f1 * 100) : "-");
            out.add(line);
        }
        return out;
    }

    /** Every generator level of every dimension with its full block table, cost and requirements. */
    public void generatorLevels(Player p, Island i) {
        Menu m = new Menu(6, "<dark_gray>Generator levels");
        Map<Dim, Material> header = Map.of(Dim.OVERWORLD, Material.COBBLESTONE, Dim.NETHER, Material.NETHERRACK, Dim.END, Material.END_STONE);
        int row = 0;
        for (Dim d : Dim.values()) {
            int cur = GeneratorService.level(i, d);
            m.set(row * 9, Menu.icon(header.get(d), "<gold>" + d.display + " generator", List.of(
                    cur == 0 ? "<gray>Not bought yet" : "Your level: <white>" + cur)), null);
            int slot = row * 9 + 1;
            for (Settings.GenLevel g : plugin.settings.gens.get(d).values()) {
                if (slot >= row * 9 + 9) break;
                boolean owned = g.level() <= cur;
                List<String> lore = new ArrayList<>();
                lore.add(g.level() == cur ? "<green>Your current level" : owned ? "<gray>Owned" : g.level() == cur + 1 ? "<yellow>Next upgrade" : "<dark_gray>Locked");
                lore.add("");
                lore.addAll(oddsLines(g, null));
                if (!owned) {
                    lore.add("");
                    lore.add("Requires island level: <white>" + g.islandLevel());
                    lore.add("Requires value: <white>" + Text.compact(g.cost().value()));
                    lore.add("Cost: " + Text.roses(g.cost().roses()) + " <gray>+ " + Text.dollars(g.cost().dollars()));
                }
                Material icon = g.level() == cur ? Material.LIME_STAINED_GLASS_PANE : owned ? Material.GREEN_STAINED_GLASS_PANE
                        : g.level() == cur + 1 ? Material.YELLOW_STAINED_GLASS_PANE : Material.GRAY_STAINED_GLASS_PANE;
                m.set(slot++, Menu.icon(icon, "<yellow>" + d.display + " level " + g.level(), lore, g.level() == cur), null);
            }
            row += 2;
        }
        m.set(49, Menu.back(), e -> upgrades(p, i));
        m.open(p);
    }

    /** "-20% grow time (special 8m, normal x2.5)" for a Crop Growth level. */
    private String growthText(Island i, int level) {
        double r = plugin.settings.growthReduction(level);
        long special = Math.round(plugin.settings.specialCropSeconds * (1 - r));
        return "-" + Math.round(r * 100) + "% grow time <gray>(special " + Text.duration(special)
                + ", normal x" + Text.num(Math.round(plugin.settings.normalCropSpeed / (1 - r) * 10) / 10.0) + " speed)";
    }

    private String currentText(Island i, UpgradeService.Kind k) {
        return switch (k) {
            case LEVEL -> "Level " + i.level + " (" + plugin.settings.islandLevel(i.level).size() + "x" + plugin.settings.islandLevel(i.level).size() + ")";
            case SELL -> "x" + Text.num(plugin.pricing.islandMultiplier(i));
            case GROWTH -> growthText(i, i.growthLevel);
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
            case GROWTH -> growthText(i, next);
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
