package dev.skycore.ops;

import dev.skycore.SkyCore;
import dev.skycore.data.Island;
import dev.skycore.data.Profile;
import dev.skycore.island.UpgradeService;
import dev.skycore.items.SpecialItems;
import dev.skycore.market.AuctionHouse;
import dev.skycore.ranking.WeeklyRanking;
import dev.skycore.util.Keys;
import dev.skycore.util.Pos;
import dev.skycore.util.Text;
import dev.skycore.world.Dim;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * /sc selftest - exercises the core mechanics on a real, temporary island inside the running server
 * (generator, minions with offline cap, spawners, sell chest delivery, upgrade gating, soulbound rules)
 * and cleans up afterwards. Safe to run on a live server; uses a dedicated fake account.
 */
public final class SelfTest {
    private final SkyCore plugin;
    private final List<String> results = new ArrayList<>();
    private int failures;

    public SelfTest(SkyCore plugin) {
        this.plugin = plugin;
    }

    private void check(String name, boolean ok, String detail) {
        results.add((ok ? "<green>PASS " : "<red>FAIL ") + "<white>" + name + " <gray>" + detail);
        if (!ok) failures++;
    }

    public void run(CommandSender s) {
        UUID u = UUID.nameUUIDFromBytes("skycore-selftest".getBytes());
        Profile prof = plugin.data.profileOrCreate(u, "SkyCoreSelfTest");
        prof.staff = true;

        // ---------------------------------------------------------------- pure rules
        double x50 = plugin.skills.xpForLevel(50), x10 = plugin.skills.xpForLevel(10), x100 = plugin.skills.xpForLevel(100);
        check("XP curve", Math.abs(x10 - 7924) < 200 && Math.abs(x50 - 272_000) < 5000 && Math.abs(x100 - 1_256_000) < 20_000,
                "L10=" + Math.round(x10) + " L50=" + Math.round(x50) + " L100=" + Math.round(x100));
        check("Roses per payout", plugin.skills.rosesPerPayout(1) == 1 && plugin.skills.rosesPerPayout(20) == 2
                && plugin.skills.rosesPerPayout(99) == 5 && plugin.skills.rosesPerPayout(100) == 6, "1/2/5/6 at levels 1/20/99/100");
        check("Minion is soulbound and not tradable", SpecialItems.isSoulbound(plugin.items.minion(u, "cobblestone", 1))
                && !AuctionHouse.tradable(plugin.items.minion(u, "cobblestone", 1)), "");
        check("Spawner units are tradable", AuctionHouse.tradable(plugin.items.spawner("zombie", 5)), "");
        double cap = plugin.settings.config.getDouble("economy.sell-multiplier-cap", 4.0);
        prof.stars = 10;
        double m = plugin.pricing.multiplier(u, null, dev.skycore.skills.Skill.MINING);
        prof.stars = 0;
        check("Sell multiplier respects cap", m <= cap + 1e-9, "x" + Text.num(m) + " <= x" + cap);

        Text.msg(s, "Self-test: creating a temporary island...");
        plugin.islands.createFor(u, 1, i -> Bukkit.getScheduler().runTask(plugin, () -> phaseIsland(s, prof, i)));
    }

    private void phaseIsland(CommandSender s, Profile prof, Island i) {
        Location c = plugin.islands.center(i, Dim.OVERWORLD);
        World w = c.getWorld();
        w.addPluginChunkTicket(c.getBlockX() >> 4, c.getBlockZ() >> 4, plugin);
        int x = c.getBlockX(), y = c.getBlockY(), z = c.getBlockZ();
        check("Starter island built", w.getBlockAt(x, y - 1, z).getType() == Material.GRASS_BLOCK
                && w.getBlockAt(x + 2, y, z - 2).getType() == Material.CHEST, "at " + x + "," + y + "," + z);
        check("Island lookup by location", plugin.islands.at(c) == i, "cell " + i.gx + "," + i.gz);
        check("Outside the border is not the island", plugin.islands.at(c.clone().add(25, 0, 0)) == null, "size 40 at level 1");

        UpgradeService.Offer o = plugin.upgrades.offer(i, UpgradeService.Kind.LEVEL);
        check("Upgrade blocked without value/Roses/Dollars", o.missing().size() == 3, String.join(", ", o.missing()));
        prof.roses += 40;
        prof.dollars += 10_000;
        i.blockValue = 5000;
        o = plugin.upgrades.offer(i, UpgradeService.Kind.LEVEL);
        check("Upgrade allowed with all three", o.missing().isEmpty(), "level 1 -> 2");
        prof.roses = 0;
        prof.dollars = 0;
        i.blockValue = 0;

        // the pre-built generator needs ~10 s of fluid ticks
        Bukkit.getScheduler().runTaskLater(plugin, () -> phaseAutomation(s, prof, i), 240L);
    }

    private void phaseAutomation(CommandSender s, Profile prof, Island i) {
        Location c = plugin.islands.center(i, Dim.OVERWORLD);
        World w = c.getWorld();
        int x = c.getBlockX(), y = c.getBlockY(), z = c.getBlockZ();
        Material formed = w.getBlockAt(x - 1, y - 1, z + 2).getType();
        check("Generator produced a level-1 block", plugin.generators.produces(i, Dim.OVERWORLD, formed), "got " + formed);

        long now = System.currentTimeMillis();
        long capMs = plugin.settings.config.getLong("automation.offline-cap-hours", 12) * 3_600_000L;

        // minion with internal storage
        Island.Minion mn = new Island.Minion();
        mn.id = UUID.randomUUID().toString();
        mn.resource = "cobblestone";
        mn.tier = 1;
        mn.owner = prof.uuid;
        mn.pos = new Pos(w.getName(), x - 3, y, z + 3).key();
        mn.last = now - 3_600_000L;
        i.minions.put(mn.pos, mn);
        plugin.minions.produce(i, mn, now, capMs);
        check("Minion produces 1 item per cycle", mn.stored == 120, "1h at 30s = " + mn.stored + " (expected 120)");
        mn.last = now - 48 * 3_600_000L;
        mn.stored = 0;
        plugin.minions.produce(i, mn, now, capMs);
        check("Minion storage cap", mn.stored == 3 * 64, "stored " + mn.stored);

        // minion linked to a sell chest: 12 h offline cap, Dollars to the owner
        Block chest = w.getBlockAt(x - 3, y, z + 1);
        chest.setType(Material.CHEST);
        i.sellChests.add(Pos.key(chest));
        double before = prof.dollars;
        mn.last = now - 48 * 3_600_000L;
        mn.stored = 0;
        plugin.minions.produce(i, mn, now, capMs);
        double expected = plugin.pricing.batchValue(Material.COBBLESTONE, 12 * 3600 / 30, prof.uuid, i);
        check("Linked minion sells, capped at 12h offline", Math.abs(prof.dollars - before - expected) < 0.01 && mn.stored == 0,
                "+" + Text.money(prof.dollars - before) + " (expected " + Text.money(expected) + ")");
        i.minions.clear();

        // spawner far from the sell chest
        Block sp = w.getBlockAt(x + 3, y, z - 3);
        sp.setType(Material.SPAWNER);
        Island.SpawnerData sd = new Island.SpawnerData();
        sd.type = "zombie";
        sd.stack = 30;
        sd.last = now - 3_600_000L;
        i.spawners.put(Pos.key(sp), sd);
        plugin.spawners.cycle(i, Pos.key(sp), sd, now);
        Entity ent = sd.entity == null ? null : Bukkit.getEntity(UUID.fromString(sd.entity));
        String tag = ent == null ? null : ent.getPersistentDataContainer().get(Keys.MOB, PersistentDataType.STRING);
        check("Spawner keeps one entity for the stack", ent != null && tag != null && sd.entityCount == 10, "entity represents " + sd.entityCount + " mobs");
        long stored = sd.storage.values().stream().mapToLong(Long::longValue).sum();
        check("Spawner virtual harvest (1h of 30 units)", stored >= 3400, stored + " items stored (1790 mobs x 2 flesh, capped 3456)");
        if (ent != null) ent.remove();
        i.spawners.clear();
        sp.setType(Material.AIR);

        // sell chest selling real items
        if (chest.getState() instanceof org.bukkit.block.Chest ch) {
            ch.getBlockInventory().addItem(new org.bukkit.inventory.ItemStack(Material.DIAMOND, 10),
                    plugin.items.minion(prof.uuid, "coal", 1));
            before = prof.dollars;
            var sale = plugin.pricing.sellInventory(ch.getBlockInventory(), prof.uuid, i, 1.0, true);
            plugin.sellChests.credit(i, sale.total(), sale.items(), "selftest");
            boolean minionKept = ch.getBlockInventory().contains(Material.PLAYER_HEAD);
            check("Sell chest sells priced items, keeps special items", sale.items() == 10 && minionKept && prof.dollars > before,
                    "+" + Text.money(prof.dollars - before) + " for 10 diamonds");
        }

        check("Weekly standings compute", plugin.ranking.standings(plugin.ranking.currentWeek(), WeeklyRanking.Board.TRADE) != null, "");

        plugin.islands.ensureDimension(i, Dim.NETHER, () -> Bukkit.getScheduler().runTask(plugin, () -> {
            Location n = plugin.islands.center(i, Dim.NETHER);
            check("Nether mini-island with portal", n.getWorld().getBlockAt(n.getBlockX(), n.getBlockY(), n.getBlockZ() - 3).getType() == Material.NETHER_PORTAL, "");
            finish(s, prof, i);
        }));
    }

    private void finish(CommandSender s, Profile prof, Island i) {
        Location c = plugin.islands.center(i, Dim.OVERWORLD);
        c.getWorld().removePluginChunkTicket(c.getBlockX() >> 4, c.getBlockZ() >> 4, plugin);
        plugin.islands.retire(i);
        // the retired island keeps its grid cell reserved so no new island is placed on top of the test blocks
        plugin.data.removeProfile(prof.uuid);
        plugin.islands.reindex();
        Text.raw(s, "<gold>SkyCore self-test results");
        for (String r : results) Text.raw(s, " " + r);
        Text.raw(s, failures == 0 ? "<green>All " + results.size() + " checks passed." : "<red>" + failures + " of " + results.size() + " checks failed.");
        plugin.getLogger().info("Self-test finished: " + (results.size() - failures) + "/" + results.size() + " passed");
    }
}
