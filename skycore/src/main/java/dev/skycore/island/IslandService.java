package dev.skycore.island;

import dev.skycore.SkyCore;
import dev.skycore.config.Settings;
import dev.skycore.data.Island;
import dev.skycore.data.Profile;
import dev.skycore.util.Text;
import dev.skycore.world.Dim;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.function.Consumer;

/**
 * Island grid, lookup by location, creation, membership, borders and island value.
 * Each island owns one grid cell; the same cell is used in the Nether and End worlds.
 */
public final class IslandService {
    private final SkyCore plugin;
    public final IslandBuilder builder;
    private final Map<Long, Integer> cells = new HashMap<>();
    private final Map<UUID, Set<Integer>> access = new HashMap<>();
    private final List<int[]> spiral = new ArrayList<>();
    private final Map<UUID, String> borderState = new HashMap<>();
    private final Map<UUID, UUID> invites = new HashMap<>();
    private final Map<UUID, Integer> inviteIsland = new HashMap<>();

    public IslandService(SkyCore plugin) {
        this.plugin = plugin;
        this.builder = new IslandBuilder(plugin);
        spiral.add(new int[]{0, 0});
    }

    // ------------------------------------------------------------------ indexing
    private static long cellKey(int gx, int gz) {
        return ((long) gx << 32) | (gz & 0xFFFFFFFFL);
    }

    public void reindex() {
        cells.clear();
        access.clear();
        for (Island i : plugin.data.islands.values()) {
            cells.put(cellKey(i.gx, i.gz), i.id);
            if (i.retired) continue;
            access.computeIfAbsent(i.owner, k -> new HashSet<>()).add(i.id);
            for (UUID m : i.members) access.computeIfAbsent(m, k -> new HashSet<>()).add(i.id);
        }
    }

    private int[] cell(int n) {
        while (spiral.size() <= n) {
            // square spiral walk: right, up, left, down with growing legs
            int i = spiral.size();
            int[] last = spiral.get(i - 1);
            // recompute direction from index: legs lengths 1,1,2,2,3,3...
            int idx = i - 1, leg = 1, dir = 0;
            while (idx >= leg) {
                idx -= leg;
                dir = (dir + 1) % 4;
                if (dir % 2 == 0) leg++;
            }
            int[] d = switch (dir) {
                case 0 -> new int[]{1, 0};
                case 1 -> new int[]{0, 1};
                case 2 -> new int[]{-1, 0};
                default -> new int[]{0, -1};
            };
            spiral.add(new int[]{last[0] + d[0], last[1] + d[1]});
        }
        return spiral.get(n);
    }

    // ------------------------------------------------------------------ geometry
    public int spacing() {
        return plugin.settings.spacing;
    }

    public int centerX(Island i) {
        return i.gx * spacing();
    }

    public int centerZ(Island i) {
        return i.gz * spacing();
    }

    public int size(Island i, Dim d) {
        int s = plugin.settings.islandLevel(i.level).size();
        if (d != Dim.OVERWORLD) s = Math.max(plugin.settings.miniMinSize, (int) Math.round(s * plugin.settings.miniRatio));
        return s;
    }

    public Location center(Island i, Dim d) {
        return new Location(plugin.worlds.world(d), centerX(i), plugin.settings.islandHeight, centerZ(i));
    }

    /** Island whose grid cell contains this location (ignores bounds). */
    public Island cellIsland(Location l) {
        if (l == null || !plugin.worlds.isIslandWorld(l.getWorld())) return null;
        int gx = (int) Math.round(l.getX() / spacing());
        int gz = (int) Math.round(l.getZ() / spacing());
        Integer id = cells.get(cellKey(gx, gz));
        if (id == null) return null;
        Island i = plugin.data.islands.get(id);
        return i == null || i.retired ? null : i;
    }

    /** Island at this location, only inside its current bounds. */
    public Island at(Location l) {
        Island i = cellIsland(l);
        return i != null && inBounds(i, l.getWorld(), l.getBlockX(), l.getBlockZ()) ? i : null;
    }

    public Island at(Block b) {
        return at(b.getLocation());
    }

    public boolean inBounds(Island i, World w, int bx, int bz) {
        Dim d = plugin.worlds.dim(w);
        if (d == null) return false;
        int half = size(i, d) / 2;
        int cx = centerX(i), cz = centerZ(i);
        return bx >= cx - half && bx < cx + half && bz >= cz - half && bz < cz + half;
    }

    // ------------------------------------------------------------------ queries
    public Island get(int id) {
        Island i = plugin.data.islands.get(id);
        return i == null || i.retired ? null : i;
    }

    public List<Island> owned(UUID u) {
        Profile p = plugin.data.profile(u);
        List<Island> out = new ArrayList<>();
        if (p == null) return out;
        for (int id : p.islands) {
            Island i = get(id);
            if (i != null) out.add(i);
        }
        return out;
    }

    public Island main(UUID u) {
        List<Island> o = owned(u);
        return o.isEmpty() ? null : o.getFirst();
    }

    public List<Island> accessible(UUID u) {
        List<Island> out = new ArrayList<>();
        for (int id : access.getOrDefault(u, Set.of())) {
            Island i = get(id);
            if (i != null) out.add(i);
        }
        out.sort(Comparator.comparingInt(i -> i.id));
        return out;
    }

    public int highestLevel(UUID u) {
        int max = 0;
        for (Island i : owned(u)) max = Math.max(max, i.level);
        return Math.max(1, max);
    }

    public boolean canBuild(Player p, Location l) {
        if (p.hasPermission("skycore.bypass")) return true;
        if (!plugin.worlds.isIslandWorld(l.getWorld())) return true;
        Island i = at(l);
        return i != null && i.isMember(p.getUniqueId());
    }

    /** Islands with at least one owner/member online: only these run automation. */
    public Set<Island> active() {
        Set<Island> out = new HashSet<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            for (int id : access.getOrDefault(p.getUniqueId(), Set.of())) {
                Island i = get(id);
                if (i != null) out.add(i);
            }
        }
        return out;
    }

    public String ownerName(Island i) {
        Profile p = plugin.data.profile(i.owner);
        return p == null ? "?" : p.name;
    }

    public int indexOf(Island i) {
        Profile p = plugin.data.profile(i.owner);
        return p == null ? 1 : p.islands.indexOf(i.id) + 1;
    }

    public String label(Island i) {
        return ownerName(i) + "'s island #" + indexOf(i);
    }

    // ------------------------------------------------------------------ value
    public double value(Island i) {
        var w = plugin.settings.prices;
        double v = i.blockValue;
        double unitFactor = w.getDouble("island-value.spawner-unit-factor", 0.25);
        for (Island.SpawnerData s : i.spawners.values()) {
            Settings.SpawnerType t = plugin.settings.spawnerTypes.get(s.type);
            if (t != null) v += s.stack * t.dollars() * unitFactor;
        }
        double mf = w.getDouble("island-value.minion-factor", 0.5);
        for (Island.Minion m : i.minions.values()) v += m.dollarsSpent * mf;
        v += i.sellChests.size() * w.getDouble("island-value.sell-chest", 5000);
        v += i.shops.size() * w.getDouble("island-value.shop-chest", 10000);
        v += i.chunkHoppers.size() * w.getDouble("island-value.chunk-hopper", 25000);
        v += i.hoppers * w.getDouble("island-value.hopper", 100);
        v += i.crops.size() * w.getDouble("island-value.special-crop", 500);
        return Math.max(0, v);
    }

    public double blockWorth(org.bukkit.Material m) {
        if (m == org.bukkit.Material.WHEAT || m == org.bukkit.Material.NETHER_WART) return 0; // crop blocks
        Double s = plugin.settings.storageBlockValue.get(m);
        if (s != null) return s;
        return plugin.settings.sellPrice(m);
    }

    public void addBlockValue(Island i, double delta) {
        if (delta == 0) return;
        i.blockValue = Math.max(0, i.blockValue + delta);
        plugin.data.dirty(i);
    }

    public double totalOwnedValue(UUID u) {
        double v = 0;
        for (Island i : owned(u)) v += value(i);
        return v;
    }

    // ------------------------------------------------------------------ creation
    public void create(Player p, int startLevel, Consumer<Island> after) {
        plugin.data.profileOrCreate(p.getUniqueId(), p.getName());
        createFor(p.getUniqueId(), startLevel, i -> {
            Location home = home(i);
            p.teleportAsync(home);
            Text.msg(p, "<green>Your island is ready! <gray>Use <white>/is</white> for the island menu and <white>/path</white> for your next goals.");
            if (after != null) after.accept(i);
        });
    }

    /** Allocates a grid cell, builds the starter island and registers it (owner need not be online). */
    public Island createFor(UUID owner, int startLevel, Consumer<Island> after) {
        Profile prof = plugin.data.profile(owner);
        var g = plugin.data.global;
        if (g.spacingAtCreation == 0) g.spacingAtCreation = spacing();
        int[] c;
        do {
            c = cell(g.nextGridIndex++);
        } while (cells.containsKey(cellKey(c[0], c[1])) || (c[0] == 0 && c[1] == 0));
        Island i = new Island();
        i.id = g.nextIslandId++;
        i.owner = owner;
        i.gx = c[0];
        i.gz = c[1];
        i.level = Math.max(1, Math.min(startLevel, plugin.settings.maxIslandLevel()));
        i.created = System.currentTimeMillis();
        i.lastActive = i.created;
        plugin.data.islands.put(i.id, i);
        prof.islands.add(i.id);
        plugin.data.dirty(i);
        plugin.data.dirty(prof);
        plugin.data.dirtyGlobal();
        reindex();
        Location center = center(i, Dim.OVERWORLD);
        center.getWorld().getChunkAtAsync(center).thenAccept(chunk -> {
            builder.overworld(center);
            Location home = center.clone().add(0.5, 0, 0.5);
            home.setYaw(180);
            i.home = new Island.Loc(home);
            plugin.data.dirty(i);
            if (after != null) after.accept(i);
        });
        return i;
    }

    public void ensureDimension(Island i, Dim d, Runnable then) {
        boolean created = d == Dim.NETHER ? i.netherCreated : d == Dim.END ? i.endCreated : true;
        if (created) {
            then.run();
            return;
        }
        Location c = center(i, d);
        c.getWorld().getChunkAtAsync(c).thenAccept(chunk -> {
            if (d == Dim.NETHER) {
                builder.nether(c);
                i.netherCreated = true;
            } else {
                builder.end(c);
                i.endCreated = true;
            }
            plugin.data.dirty(i);
            then.run();
        });
    }

    public Location home(Island i) {
        Location l = i.home == null ? null : i.home.toLocation();
        if (l == null) l = center(i, Dim.OVERWORLD).add(0.5, 0, 0.5);
        return l;
    }

    public Location dimensionHome(Island i, Dim d) {
        if (d == Dim.OVERWORLD) return home(i);
        return center(i, d).add(0.5, 0, 0.5);
    }

    public void teleport(Player p, Location l) {
        if (l == null) return;
        l.getWorld().getChunkAtAsync(l).thenAccept(ch -> p.teleportAsync(l));
    }

    // ------------------------------------------------------------------ extra islands
    public void buyExtra(Player p) {
        Profile prof = plugin.data.profile(p.getUniqueId());
        List<Island> owned = owned(p.getUniqueId());
        int next = owned.size() + 1;
        if (owned.isEmpty()) {
            create(p, startLevel(prof), null);
            return;
        }
        Settings.ExtraIsland ex = plugin.settings.extraIslands.get(next);
        if (ex == null) {
            Text.msg(p, "<red>You already own the maximum number of islands.");
            return;
        }
        Island main = owned.getFirst();
        if (main.level < ex.mainLevel()) {
            Text.msg(p, "<red>Your main island must be level " + ex.mainLevel() + " to buy island #" + next + ".");
            return;
        }
        if (!plugin.eco.charge(p.getUniqueId(), ex.roses(), ex.dollars(), "extra_island")) {
            Text.msg(p, "<red>You need " + Text.roses(ex.roses()) + " <red>and " + Text.dollars(ex.dollars()) + "<red>.");
            return;
        }
        create(p, startLevel(prof), i -> {
            Text.msg(p, "<green>Island #" + next + " purchased!");
            plugin.islandMissions.refreshOwner(p.getUniqueId());
        });
    }

    public int startLevel(Profile prof) {
        int max = plugin.settings.config.getInt("prestige.max-start-level", 5);
        return Math.min(max, 1 + prof.stars);
    }

    // ------------------------------------------------------------------ members
    public void invite(Player owner, Island i, Player target) {
        if (!i.owner.equals(owner.getUniqueId())) {
            Text.msg(owner, "<red>Only the island owner can invite members.");
            return;
        }
        if (i.members.size() >= plugin.settings.maxMembers) {
            Text.msg(owner, "<red>This island already has the maximum of " + plugin.settings.maxMembers + " members.");
            return;
        }
        if (i.isMember(target.getUniqueId())) {
            Text.msg(owner, "<red>" + target.getName() + " is already a member.");
            return;
        }
        invites.put(target.getUniqueId(), owner.getUniqueId());
        inviteIsland.put(target.getUniqueId(), i.id);
        Text.msg(owner, "Invited <white>" + target.getName() + "</white> to " + Text.escape(label(i)) + ".");
        Text.msg(target, "<white>" + owner.getName() + "</white> invited you to their island. <green><click:run_command:'/is accept'>[Click or type /is accept]</click>");
    }

    public void accept(Player p) {
        Integer id = inviteIsland.remove(p.getUniqueId());
        invites.remove(p.getUniqueId());
        Island i = id == null ? null : get(id);
        if (i == null) {
            Text.msg(p, "<red>You have no pending invite.");
            return;
        }
        if (i.members.size() >= plugin.settings.maxMembers) {
            Text.msg(p, "<red>That island is full.");
            return;
        }
        i.members.add(p.getUniqueId());
        plugin.data.dirty(i);
        reindex();
        Text.msg(p, "<green>You joined " + Text.escape(label(i)) + ". Roses and Dollars stay personal.");
        Player o = Bukkit.getPlayer(i.owner);
        if (o != null) Text.msg(o, "<white>" + p.getName() + "</white> joined your island.");
        teleport(p, home(i));
    }

    public void kick(Player owner, Island i, String name) {
        if (!i.owner.equals(owner.getUniqueId())) {
            Text.msg(owner, "<red>Only the owner can remove members.");
            return;
        }
        Profile t = plugin.data.byName(name);
        if (t == null || !i.members.remove(t.uuid)) {
            Text.msg(owner, "<red>" + name + " is not a member.");
            return;
        }
        plugin.data.dirty(i);
        reindex();
        Text.msg(owner, "Removed " + t.name + ".");
        Player tp = Bukkit.getPlayer(t.uuid);
        if (tp != null) {
            Text.msg(tp, "<red>You were removed from " + Text.escape(label(i)) + ".");
            if (at(tp.getLocation()) == i) tp.teleportAsync(plugin.worlds.spawn());
        }
    }

    public void leave(Player p, Island i) {
        if (i.owner.equals(p.getUniqueId())) {
            Text.msg(p, "<red>Owners cannot leave their own island.");
            return;
        }
        if (i.members.remove(p.getUniqueId())) {
            plugin.data.dirty(i);
            reindex();
            Text.msg(p, "You left " + Text.escape(label(i)) + ".");
            p.teleportAsync(plugin.worlds.spawn());
        }
    }

    /** Prestige: islands are retired (grid cell stays reserved), members lose access. */
    public void retire(Island i) {
        i.retired = true;
        plugin.data.dirty(i);
        for (Player p : Bukkit.getOnlinePlayers()) {
            Island here = cellIsland(p.getLocation());
            if (here == null && plugin.worlds.isIslandWorld(p.getWorld())) {
                int gx = (int) Math.round(p.getLocation().getX() / spacing());
                int gz = (int) Math.round(p.getLocation().getZ() / spacing());
                if (gx == i.gx && gz == i.gz) p.teleportAsync(plugin.worlds.spawn());
            }
        }
        for (String key : new ArrayList<>(i.shops)) plugin.data.removeShop(key);
        reindex();
    }

    // ------------------------------------------------------------------ borders
    public void tickBorders() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            Location l = p.getLocation();
            Island i = cellIsland(l);
            if (i == null) {
                if (borderState.remove(p.getUniqueId()) != null) p.setWorldBorder(null);
                continue;
            }
            Dim d = plugin.worlds.dim(l.getWorld());
            int size = size(i, d);
            String state = i.id + ":" + d + ":" + size;
            if (state.equals(borderState.get(p.getUniqueId()))) continue;
            WorldBorder b = Bukkit.createWorldBorder();
            b.setCenter(centerX(i), centerZ(i));
            b.setSize(size);
            b.setWarningDistance(0);
            b.setDamageAmount(0);
            p.setWorldBorder(b);
            borderState.put(p.getUniqueId(), state);
        }
    }

    public void forgetBorder(UUID u) {
        borderState.remove(u);
    }
}
