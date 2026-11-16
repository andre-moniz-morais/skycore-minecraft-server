package dev.skycore.cmd;

import dev.skycore.SkyCore;
import dev.skycore.data.Island;
import dev.skycore.data.Listing;
import dev.skycore.data.Profile;
import dev.skycore.skills.Skill;
import dev.skycore.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.*;
import java.util.stream.Collectors;

/** Every player and admin command. */
public final class Commands implements CommandExecutor, TabCompleter {
    private final SkyCore plugin;

    public Commands(SkyCore plugin) {
        this.plugin = plugin;
    }

    public void register() {
        for (String c : List.of("island", "balance", "pay", "baltop", "roses", "shop", "sell", "skills", "pets", "ah", "shops",
                "missions", "path", "prestige", "top", "rewards", "wand", "spawn", "skycore")) {
            var cmd = plugin.getCommand(c);
            if (cmd != null) {
                cmd.setExecutor(this);
                cmd.setTabCompleter(this);
            }
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        if (name.equals("skycore")) return admin(sender, args);
        if (name.equals("baltop")) {
            baltop(sender);
            return true;
        }
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Players only.");
            return true;
        }
        plugin.data.profileOrCreate(p.getUniqueId(), p.getName());
        switch (name) {
            case "island" -> island(p, args);
            case "balance" -> balance(p, args);
            case "pay" -> pay(p, args);
            case "roses" -> Text.msg(p, "Roses: " + Text.roses(plugin.eco.roses(p.getUniqueId()))
                    + " <dark_gray>- earned only from skills, never transferable.");
            case "shop" -> plugin.shopMenus.main(p);
            case "sell" -> {
                if (args.length > 0 && args[0].equalsIgnoreCase("all")) plugin.sell.sellAll(p);
                else if (args.length > 0 && args[0].equalsIgnoreCase("hand")) plugin.sell.sellHand(p);
                else Text.msg(p, "Usage: <white>/sell hand</white> or <white>/sell all");
            }
            case "skills" -> plugin.goalMenus.skills(p);
            case "pets" -> plugin.pets.menu(p);
            case "ah" -> ah(p, args);
            case "shops" -> {
                if (args.length == 0) Text.msg(p, "Usage: <white>/shops <item>");
                else plugin.playerShops.find(p, String.join("_", args));
            }
            case "missions" -> plugin.goalMenus.missions(p);
            case "path" -> plugin.goalMenus.path(p);
            case "prestige" -> plugin.prestige.run(p, args.length > 0 && args[0].equalsIgnoreCase("confirm"));
            case "top" -> plugin.ranking.show(p);
            case "rewards" -> plugin.rewards.openBox(p);
            case "wand" -> {
                if (args.length >= 2 && args[0].equalsIgnoreCase("recharge")) {
                    try {
                        plugin.sell.recharge(p, Long.parseLong(args[1]));
                    } catch (NumberFormatException e) {
                        Text.msg(p, "<red>Not a number.");
                    }
                } else Text.msg(p, "Usage: <white>/wand recharge <uses></white> <gray>(" + Text.money(plugin.settings.automation.getDouble("sell-wands.recharge-cost-per-use", 200)) + " per use)");
            }
            case "spawn" -> plugin.islands.teleport(p, plugin.worlds.spawn());
            default -> {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ /is
    private void island(Player p, String[] a) {
        UUID u = p.getUniqueId();
        Island ctx = plugin.islandMenus.context(p);
        String sub = a.length == 0 ? "" : a[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "" -> {
                if (ctx == null) plugin.islands.buyExtra(p);
                else plugin.islandMenus.main(p, ctx);
            }
            case "create" -> plugin.islands.buyExtra(p);
            case "home", "go", "tp" -> {
                List<Island> acc = plugin.islands.accessible(u);
                if (acc.isEmpty()) {
                    Text.msg(p, "<red>You have no island. Type /is to create one.");
                    return;
                }
                Island target = ctx;
                if (a.length > 1) {
                    try {
                        int n = Integer.parseInt(a[1]);
                        List<Island> owned = plugin.islands.owned(u);
                        target = n >= 1 && n <= owned.size() ? owned.get(n - 1) : null;
                    } catch (NumberFormatException e) {
                        target = null;
                    }
                    if (target == null) {
                        Text.msg(p, "<red>Unknown island number.");
                        return;
                    }
                }
                plugin.islands.teleport(p, plugin.islands.home(target));
            }
            case "list" -> plugin.islandMenus.list(p);
            case "sethome" -> {
                Island here = plugin.islands.at(p.getLocation());
                if (here == null || !here.isMember(u) || plugin.worlds.dim(p.getWorld()) != dev.skycore.world.Dim.OVERWORLD) {
                    Text.msg(p, "<red>Stand on the Overworld part of your island.");
                    return;
                }
                here.home = new Island.Loc(p.getLocation());
                plugin.data.dirty(here);
                Text.msg(p, "<green>Island home set.");
            }
            case "setwarp" -> {
                Island here = plugin.islands.at(p.getLocation());
                if (here == null || !here.owner.equals(u)) {
                    Text.msg(p, "<red>Stand on an island you own.");
                    return;
                }
                here.shopWarp = new Island.Loc(p.getLocation());
                plugin.data.dirty(here);
                Text.msg(p, "<green>Shop warp set. Players using /shops will arrive here.");
            }
            case "warp" -> {
                if (a.length < 2) {
                    Text.msg(p, "Usage: /is warp <player>");
                    return;
                }
                Profile t = plugin.data.byName(a[1]);
                Island ti = t == null ? null : plugin.islands.main(t.uuid);
                if (ti == null) {
                    Text.msg(p, "<red>That player has no island.");
                    return;
                }
                plugin.islands.teleport(p, ti.shopWarp != null ? ti.shopWarp.toLocation() : plugin.islands.home(ti));
            }
            case "visit" -> {
                if (a.length < 2) {
                    Text.msg(p, "Usage: /is visit <player>");
                    return;
                }
                Profile t = plugin.data.byName(a[1]);
                Island ti = t == null ? null : plugin.islands.main(t.uuid);
                if (ti == null) {
                    Text.msg(p, "<red>That player has no island.");
                    return;
                }
                plugin.islands.teleport(p, plugin.islands.home(ti));
                Text.msg(p, "Visiting " + Text.escape(plugin.islands.label(ti)) + ".");
            }
            case "invite" -> {
                Player t = a.length > 1 ? Bukkit.getPlayerExact(a[1]) : null;
                if (t == null || ctx == null) {
                    Text.msg(p, "<red>Usage: /is invite <online player> <gray>(invites to the island you are on, or your main island)");
                    return;
                }
                plugin.islands.invite(p, ctx, t);
            }
            case "accept" -> plugin.islands.accept(p);
            case "kick", "remove" -> {
                if (a.length < 2 || ctx == null) {
                    Text.msg(p, "Usage: /is kick <player>");
                    return;
                }
                plugin.islands.kick(p, ctx, a[1]);
            }
            case "leave" -> {
                Island here = plugin.islands.at(p.getLocation());
                if (here == null) {
                    Text.msg(p, "<red>Stand on the island you want to leave.");
                    return;
                }
                plugin.islands.leave(p, here);
            }
            case "members" -> {
                if (ctx != null) plugin.islandMenus.members(p, ctx);
            }
            case "upgrades", "upgrade" -> {
                if (ctx != null) plugin.islandMenus.upgrades(p, ctx);
            }
            case "missions" -> {
                if (ctx != null) plugin.islandMenus.missions(p, ctx);
            }
            case "value", "info", "level" -> {
                if (ctx == null) return;
                Text.msg(p, Text.escape(plugin.islands.label(ctx)) + ": level <white>" + ctx.level + "</white>, value <white>"
                        + Text.num(Math.floor(plugin.islands.value(ctx))) + "</white>, sell multiplier <white>x" + Text.num(plugin.pricing.islandMultiplier(ctx)));
            }
            case "top" -> {
                List<Island> all = new ArrayList<>();
                for (Island i : plugin.data.islands.values()) if (!i.retired) all.add(i);
                all.sort(Comparator.comparingDouble(i -> -plugin.islands.value(i)));
                Text.raw(p, "<gold>Top islands by value");
                for (int k = 0; k < Math.min(10, all.size()); k++)
                    Text.raw(p, " <yellow>#" + (k + 1) + "</yellow> <white>" + Text.escape(plugin.islands.label(all.get(k))) + " <gray>" + Text.compact(plugin.islands.value(all.get(k))));
            }
            default -> {
                Text.msg(p, "<white>/is</white> menu  <white>/is create</white>  <white>/is home [n]</white>  <white>/is list</white>  <white>/is upgrades</white>");
                Text.msg(p, "<white>/is invite|kick <player></white>  <white>/is accept</white>  <white>/is leave</white>  <white>/is visit <player></white>");
                Text.msg(p, "<white>/is sethome</white>  <white>/is setwarp</white>  <white>/is warp <player></white>  <white>/is value</white>  <white>/is top</white>");
            }
        }
    }

    // ------------------------------------------------------------------ economy
    private void balance(Player p, String[] a) {
        Profile t = a.length > 0 ? plugin.data.byName(a[0]) : plugin.data.profile(p.getUniqueId());
        if (t == null) {
            Text.msg(p, "<red>Unknown player.");
            return;
        }
        Text.msg(p, Text.escape(t.name) + ": " + Text.dollars(t.dollars) + " <dark_gray>|</dark_gray> " + Text.roses(t.roses));
    }

    private void pay(Player p, String[] a) {
        if (a.length < 2) {
            Text.msg(p, "Usage: /pay <player> <amount> <dark_gray>(Dollars only - Roses can never be transferred)");
            return;
        }
        Profile t = plugin.data.byName(a[0]);
        double amount;
        try {
            amount = Math.floor(Double.parseDouble(a[1]) * 100) / 100.0;
        } catch (NumberFormatException e) {
            Text.msg(p, "<red>Not a number.");
            return;
        }
        if (t == null || t.uuid.equals(p.getUniqueId())) {
            Text.msg(p, "<red>Unknown player.");
            return;
        }
        if (amount < plugin.settings.config.getDouble("economy.pay-minimum", 1)) {
            Text.msg(p, "<red>Invalid amount.");
            return;
        }
        if (!plugin.eco.transfer(p.getUniqueId(), t.uuid, amount, 0, "pay")) {
            Text.msg(p, "<red>You don't have " + Text.dollars(amount) + "<red>.");
            return;
        }
        Text.msg(p, "Sent " + Text.dollars(amount) + " <gray>to <white>" + Text.escape(t.name) + "</white>.");
        Player tp = Bukkit.getPlayer(t.uuid);
        if (tp != null) Text.msg(tp, "<white>" + p.getName() + "</white> sent you " + Text.dollars(amount) + "<gray>.");
    }

    private void baltop(CommandSender s) {
        List<Profile> all = new ArrayList<>(plugin.data.profiles.values());
        all.sort(Comparator.comparingDouble(p -> -p.dollars));
        Text.raw(s, "<gold>Richest players (Dollars)");
        for (int i = 0; i < Math.min(10, all.size()); i++)
            Text.raw(s, " <yellow>#" + (i + 1) + "</yellow> <white>" + Text.escape(all.get(i).name) + " <green>" + Text.money(all.get(i).dollars));
    }

    private void ah(Player p, String[] a) {
        if (a.length == 0) {
            plugin.ah.open(p, 0, null, null);
            return;
        }
        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "sell", "list" -> {
                if (a.length < 2) {
                    Text.msg(p, "Usage: /ah sell <total price> <gray>(lists the item in your hand)");
                    return;
                }
                try {
                    plugin.ah.list(p, Double.parseDouble(a[1].replace(",", "")));
                } catch (NumberFormatException e) {
                    Text.msg(p, "<red>Not a number.");
                }
            }
            case "search" -> plugin.ah.open(p, 0, null, a.length > 1 ? String.join(" ", Arrays.copyOfRange(a, 1, a.length)) : null);
            case "mine" -> {
                List<Listing> mine = plugin.ah.of(p.getUniqueId());
                Text.msg(p, "Your listings: <white>" + mine.size() + "/" + plugin.ah.maxListings(p.getUniqueId()) + "</white>. Click your own listing in /ah to cancel it.");
            }
            case "collect" -> plugin.rewards.openBox(p);
            default -> Text.msg(p, "/ah, /ah sell <price>, /ah search <name>, /ah mine, /ah collect");
        }
    }

    // ------------------------------------------------------------------ admin
    private boolean admin(CommandSender s, String[] a) {
        if (!s.hasPermission("skycore.admin")) {
            Text.msg(s, "<red>No permission.");
            return true;
        }
        if (a.length == 0) {
            Text.msg(s, "/sc reload | give <player> <item> [args] | roses|dollars <add|take|set> <player> <n>");
            Text.msg(s, "/sc setlevel <player> <level> | skill <player> <skill> <level> | telemetry [days] | backup | islandtp <player> | selftest");
            return true;
        }
        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> {
                plugin.reloadSettings();
                Text.msg(s, "<green>Configuration reloaded.");
            }
            case "give" -> {
                if (a.length < 3) {
                    Text.msg(s, "items: minion <res> <tier>, seeds <crop> [packs], wand <tier>, chunkhopper [n], sellchest [n], shopchest [n], spawner <type> [units], petegg <rarity>, frame [n], voucher <tier>, wanduses <n>");
                    return true;
                }
                Player t = Bukkit.getPlayerExact(a[1]);
                if (t == null) {
                    Text.msg(s, "<red>Player must be online.");
                    return true;
                }
                ItemStack it = adminItem(t.getUniqueId(), Arrays.copyOfRange(a, 2, a.length));
                if (it == null) {
                    Text.msg(s, "<red>Unknown item or arguments.");
                    return true;
                }
                plugin.rewards.giveOrBox(t.getUniqueId(), it);
                Text.msg(s, "<green>Given.");
            }
            case "roses", "dollars" -> {
                if (a.length < 4) return false;
                Profile t = plugin.data.byName(a[2]);
                if (t == null) {
                    Text.msg(s, "<red>Unknown player.");
                    return true;
                }
                double n = Double.parseDouble(a[3]);
                boolean roses = a[0].equalsIgnoreCase("roses");
                switch (a[1].toLowerCase(Locale.ROOT)) {
                    case "add" -> {
                        if (roses) plugin.eco.addRoses(t.uuid, (long) n, "admin");
                        else plugin.eco.deposit(t.uuid, n, "admin");
                    }
                    case "take" -> {
                        if (roses) plugin.eco.takeRoses(t.uuid, Math.min(t.roses, (long) n), "admin");
                        else plugin.eco.withdraw(t.uuid, Math.min(t.dollars, n), "admin");
                    }
                    case "set" -> {
                        if (roses) {
                            plugin.data.econ("roses", "admin", (long) n - t.roses);
                            t.roses = (long) n;
                            plugin.data.dirty(t);
                        } else plugin.eco.setDollars(t.uuid, n, "admin");
                    }
                    default -> {
                        return false;
                    }
                }
                Text.msg(s, "<green>Done. " + Text.escape(t.name) + ": " + Text.dollars(t.dollars) + " " + Text.roses(t.roses));
            }
            case "setlevel" -> {
                if (a.length < 3) return false;
                Profile t = plugin.data.byName(a[1]);
                Island i = t == null ? null : plugin.islands.main(t.uuid);
                if (i == null) {
                    Text.msg(s, "<red>No island.");
                    return true;
                }
                i.level = Math.max(1, Math.min(plugin.settings.maxIslandLevel(), Integer.parseInt(a[2])));
                plugin.data.dirty(i);
                Text.msg(s, "<green>Main island of " + t.name + " is now level " + i.level + ".");
            }
            case "skill" -> {
                if (a.length < 4) return false;
                Profile t = plugin.data.byName(a[1]);
                if (t == null) return true;
                Skill sk = Skill.parse(a[2]);
                t.skill(sk).xp = plugin.skills.xpForLevel(Integer.parseInt(a[3]));
                plugin.data.dirty(t);
                Text.msg(s, "<green>" + sk.display + " of " + t.name + " set to level " + a[3] + ".");
            }
            case "islandtp" -> {
                if (!(s instanceof Player p) || a.length < 2) return true;
                Profile t = plugin.data.byName(a[1]);
                Island i = t == null ? null : plugin.islands.main(t.uuid);
                if (i != null) plugin.islands.teleport(p, plugin.islands.home(i));
            }
            case "selftest" -> new dev.skycore.ops.SelfTest(plugin).run(s);
            case "telemetry" -> telemetry(s, a.length > 1 ? Integer.parseInt(a[1]) : 7);
            case "backup" -> {
                Text.msg(s, "Backup started...");
                plugin.ops.backup(() -> Text.msg(s, "<green>Backup finished."));
            }
            case "title" -> {
                if (a.length < 3) return false;
                Profile t = plugin.data.byName(a[1]);
                if (t == null) return true;
                String title = String.join(" ", Arrays.copyOfRange(a, 2, a.length));
                t.titles.add(title);
                t.activeTitle = title;
                plugin.data.dirty(t);
                Text.msg(s, "<green>Title given.");
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    private ItemStack adminItem(UUID u, String[] a) {
        try {
            return switch (a[0].toLowerCase(Locale.ROOT)) {
                case "minion" -> plugin.settings.minionResources.containsKey(a[1]) ? plugin.items.minion(u, a[1], a.length > 2 ? Integer.parseInt(a[2]) : 1) : null;
                case "seeds" -> plugin.settings.crops.containsKey(a[1]) ? plugin.items.seedPack(u, a[1], a.length > 2 ? Integer.parseInt(a[2]) : 1) : null;
                case "wand" -> {
                    int tier = Integer.parseInt(a[1]);
                    var t = plugin.settings.wandTiers.get(tier);
                    yield t == null ? null : plugin.items.wand(u, tier, t.uses());
                }
                case "chunkhopper" -> plugin.items.chunkHopper(u, a.length > 1 ? Integer.parseInt(a[1]) : 1);
                case "sellchest" -> plugin.items.sellChest(a.length > 1 ? Integer.parseInt(a[1]) : 1);
                case "shopchest" -> plugin.items.shopChest(a.length > 1 ? Integer.parseInt(a[1]) : 1);
                case "spawner" -> plugin.settings.spawnerTypes.containsKey(a[1]) ? plugin.items.spawner(a[1], a.length > 2 ? Long.parseLong(a[2]) : 1) : null;
                case "petegg" -> plugin.settings.petRarities.containsKey(a[1]) ? plugin.items.petEgg(u, a[1]) : null;
                case "frame" -> plugin.items.endFrame(a.length > 1 ? Integer.parseInt(a[1]) : 1);
                case "voucher" -> plugin.items.minionVoucher(u, Integer.parseInt(a[1]));
                case "wanduses" -> plugin.items.wandUses(u, Long.parseLong(a[1]));
                default -> null;
            };
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void telemetry(CommandSender s, int days) {
        long since = System.currentTimeMillis() - days * 86_400_000L;
        plugin.db.queryAsync("SELECT currency, source, SUM(amount), COUNT(*) FROM econ_log WHERE ts >= ? GROUP BY currency, source ORDER BY currency, SUM(amount) DESC",
                new Object[]{since}, rows -> Bukkit.getScheduler().runTask(plugin, () -> {
                    Text.raw(s, "<gold>Economy telemetry - last " + days + " days");
                    double[] created = new double[2], destroyed = new double[2];
                    for (Object[] r : rows) {
                        String cur = String.valueOf(r[0]);
                        double sum = ((Number) r[2]).doubleValue();
                        int idx = cur.equals("roses") ? 1 : 0;
                        String src = String.valueOf(r[1]);
                        if (!src.startsWith("transfer:")) {
                            if (sum >= 0) created[idx] += sum;
                            else destroyed[idx] -= sum;
                        }
                        Text.raw(s, " <gray>" + cur + " <white>" + src + " <gray>" + (sum >= 0 ? "<green>+" : "<red>") + Text.compact(sum) + " <dark_gray>(" + r[3] + ")");
                    }
                    Text.raw(s, "<white>Dollars created " + Text.compact(created[0]) + ", destroyed " + Text.compact(destroyed[0]));
                    Text.raw(s, "<white>Roses created " + Text.compact(created[1]) + ", spent " + Text.compact(destroyed[1]));
                }));
    }

    // ------------------------------------------------------------------ tab completion
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        List<String> opts = switch (name) {
            case "island" -> args.length == 1 ? List.of("create", "home", "list", "upgrades", "missions", "members", "invite", "accept", "kick",
                    "leave", "visit", "sethome", "setwarp", "warp", "value", "top") : args.length == 2 ? players() : List.of();
            case "sell" -> args.length == 1 ? List.of("hand", "all") : List.of();
            case "ah" -> args.length == 1 ? List.of("sell", "search", "mine", "collect") : List.of();
            case "pay", "balance" -> args.length == 1 ? players() : List.of();
            case "wand" -> args.length == 1 ? List.of("recharge") : List.of();
            case "prestige" -> args.length == 1 ? List.of("confirm") : List.of();
            case "skycore" -> args.length == 1 ? List.of("reload", "give", "roses", "dollars", "setlevel", "skill", "telemetry", "backup", "islandtp", "title", "selftest")
                    : args.length == 2 ? players() : List.of();
            default -> List.of();
        };
        String last = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        return opts.stream().filter(o -> o.toLowerCase(Locale.ROOT).startsWith(last)).collect(Collectors.toList());
    }

    private static List<String> players() {
        return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
    }
}
