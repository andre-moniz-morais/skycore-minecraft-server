package dev.skycore.auto;

import dev.skycore.SkyCore;
import dev.skycore.config.Settings;
import dev.skycore.data.Island;
import dev.skycore.data.Profile;
import dev.skycore.gui.Menu;
import dev.skycore.items.SpecialItems;
import dev.skycore.util.Keys;
import dev.skycore.util.Pos;
import dev.skycore.util.Text;
import dev.skycore.world.Dim;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Virtual minions: a head block with data. No ticking per minion - production is computed in batches
 * from timestamps (with a cap for offline time). Minions never break/place blocks and never give XP or Roses.
 */
public final class MinionService {
    private final SkyCore plugin;

    public MinionService(SkyCore plugin) {
        this.plugin = plugin;
    }

    public Settings.MinionResource resource(Island.Minion m) {
        return plugin.settings.minionResources.get(m.resource);
    }

    public double tierDollars(Settings.MinionResource r, int tier) {
        Settings.MinionTier t = plugin.settings.minionTiers.get(tier);
        if (t == null) return 0;
        double f = r != null && r.expensive() ? plugin.settings.automation.getDouble("minions.expensive-factor", 3) : 1;
        return t.dollars() * f;
    }

    public double accumulatedDollars(Settings.MinionResource r, int tier) {
        double sum = 0;
        for (int t = 1; t <= tier; t++) sum += tierDollars(r, t);
        return sum;
    }

    /** Requirement check for buying a resource type (tier I). Returns null if OK, else the reason. */
    public String buyRequirement(UUID buyer, Settings.MinionResource r) {
        Profile p = plugin.data.profile(buyer);
        int lvl = plugin.skills.level(p, r.skill());
        if (lvl < r.skillLevel()) return r.skill().display + " " + r.skillLevel() + " required";
        if (r.crop() != null) {
            Settings.CropType c = plugin.settings.crops.get(r.crop());
            if (c != null && lvl < c.farming()) return "Farming " + c.farming() + " required";
        }
        if (r.generatorBlock() != null) {
            boolean any = false;
            for (Island i : plugin.islands.owned(buyer))
                if (plugin.generators.produces(i, r.dimension(), r.generatorBlock())) any = true;
            if (!any) return "your " + r.dimension().display + " generator must produce " + Text.pretty(r.generatorBlock().name());
        }
        return null;
    }

    private boolean canProduce(Island i, Island.Minion m, Settings.MinionResource r) {
        if (r == null) return false;
        if (r.generatorBlock() != null && !plugin.generators.produces(i, r.dimension(), r.generatorBlock())) return false;
        Profile owner = plugin.data.profile(m.owner);
        if (owner == null) return false;
        if (r.crop() != null) {
            Settings.CropType c = plugin.settings.crops.get(r.crop());
            if (c != null && plugin.skills.level(owner, r.skill()) < c.farming()) return false;
        }
        return plugin.skills.level(owner, r.skill()) >= r.skillLevel();
    }

    // ------------------------------------------------------------------ placement
    public boolean place(Player p, Island i, Block b, ItemStack item) {
        int limit = plugin.settings.islandLevel(i.level).minions();
        if (i.minions.size() >= limit) {
            Text.msg(p, "<red>Minion limit reached (" + limit + "). Upgrade your island level for more.");
            return false;
        }
        String res = SpecialItems.data(item);
        Settings.MinionResource r = plugin.settings.minionResources.get(res);
        if (r == null) return false;
        Dim d = plugin.worlds.dim(b.getWorld());
        if (r.placeDimension() != null && r.placeDimension() != d) {
            Text.msg(p, "<red>This minion only works on your " + r.placeDimension().display + " island.");
            return false;
        }
        Island.Minion m = new Island.Minion();
        String uid = item.getItemMeta().getPersistentDataContainer().get(Keys.UID, PersistentDataType.STRING);
        m.id = uid == null ? UUID.randomUUID().toString() : uid;
        for (Island other : plugin.data.islands.values())
            for (Island.Minion om : other.minions.values())
                if (om.id.equals(m.id)) {
                    Text.msg(p, "<red>This minion is already placed.");
                    plugin.alerts.staff("dupe", p.getName() + " tried to place a duplicated minion " + m.id);
                    return false;
                }
        m.resource = res;
        m.tier = Math.max(1, SpecialItems.tier(item));
        m.owner = SpecialItems.soulboundTo(item) != null ? SpecialItems.soulboundTo(item) : p.getUniqueId();
        m.pos = Pos.key(b);
        m.last = System.currentTimeMillis();
        m.dollarsSpent = accumulatedDollars(r, m.tier);
        i.minions.put(m.pos, m);
        plugin.data.dirty(i);
        Text.msg(p, "<green>" + r.name() + " minion placed. <gray>Right-click it to manage.");
        plugin.islandMissions.check(i);
        plugin.path.check(p);
        return true;
    }

    public void pickup(Player p, Island i, Island.Minion m) {
        if (!m.owner.equals(p.getUniqueId()) && !i.owner.equals(p.getUniqueId())) {
            Text.msg(p, "<red>Only the minion's owner or the island owner can pick it up.");
            return;
        }
        collect(p, i, m);
        if (m.stored > 0) {
            Text.msg(p, "<yellow>Make room in your inventory to collect the minion's storage first.");
            return;
        }
        Block b = Pos.parse(m.pos).block();
        i.minions.remove(m.pos);
        plugin.data.dirty(i);
        if (b != null) b.setType(Material.AIR);
        ItemStack item = plugin.items.minion(m.owner, m.resource, m.tier);
        var meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(Keys.UID, PersistentDataType.STRING, m.id);
        item.setItemMeta(meta);
        plugin.rewards.giveOrBox(m.owner, item);
    }

    // ------------------------------------------------------------------ production
    public void tick() {
        long now = System.currentTimeMillis();
        long cap = plugin.settings.config.getLong("automation.offline-cap-hours", 12) * 3_600_000L;
        for (Island i : plugin.islands.active()) {
            boolean changed = false;
            for (Island.Minion m : i.minions.values()) changed |= produce(i, m, now, cap);
            if (changed) plugin.data.dirty(i);
        }
    }

    public boolean produce(Island i, Island.Minion m, long now, long cap) {
        Settings.MinionTier t = plugin.settings.minionTiers.get(m.tier);
        if (t == null) return false;
        if (m.last < now - cap) m.last = now - cap;
        long cycleMs = t.cycleSeconds() * 1000L;
        long cycles = (now - m.last) / cycleMs;
        if (cycles <= 0) return false;
        m.last += cycles * cycleMs;
        Settings.MinionResource r = resource(m);
        if (!canProduce(i, m, r)) return true;
        if (plugin.sellChests.deliver(i, Pos.parse(m.pos), r.output(), cycles, "minion")) return true;
        long capacity = (long) t.storageStacks() * r.output().getMaxStackSize();
        m.stored = Math.min(capacity, m.stored + cycles);
        return true;
    }

    public void collect(Player p, Island i, Island.Minion m) {
        Settings.MinionResource r = resource(m);
        if (r == null || m.stored <= 0) return;
        int max = r.output().getMaxStackSize();
        while (m.stored > 0) {
            int n = (int) Math.min(max, m.stored);
            Map<Integer, ItemStack> left = p.getInventory().addItem(new ItemStack(r.output(), n));
            int notAdded = left.values().stream().mapToInt(ItemStack::getAmount).sum();
            m.stored -= (n - notAdded);
            if (notAdded > 0) break;
        }
        plugin.data.dirty(i);
    }

    public void upgrade(Player p, Island i, Island.Minion m) {
        int next = m.tier + 1;
        Settings.MinionTier t = plugin.settings.minionTiers.get(next);
        Settings.MinionResource r = resource(m);
        if (t == null || r == null) {
            Text.msg(p, "<yellow>This minion is already at max tier.");
            return;
        }
        if (!m.owner.equals(p.getUniqueId())) {
            Text.msg(p, "<red>Only the minion's owner can upgrade it.");
            return;
        }
        Profile prof = plugin.data.profile(p.getUniqueId());
        if (plugin.skills.level(prof, r.skill()) < t.skillLevel()) {
            Text.msg(p, "<red>You need " + r.skill().display + " level " + t.skillLevel() + ".");
            return;
        }
        double dollars = tierDollars(r, next);
        if (!plugin.eco.charge(p.getUniqueId(), t.roses(), dollars, "minion_upgrade")) {
            Text.msg(p, "<red>You need " + Text.roses(t.roses()) + " <red>and " + Text.dollars(dollars) + "<red>.");
            return;
        }
        m.tier = next;
        m.dollarsSpent += dollars;
        plugin.data.dirty(i);
        Text.msg(p, "<green>Minion upgraded to tier " + Text.roman(next) + "!");
        plugin.islandMissions.check(i);
        plugin.path.check(p);
    }

    // ------------------------------------------------------------------ menu
    public void open(Player p, Island i, Island.Minion m) {
        Settings.MinionResource r = resource(m);
        Settings.MinionTier t = plugin.settings.minionTiers.get(m.tier);
        if (r == null || t == null) return;
        produce(i, m, System.currentTimeMillis(), plugin.settings.config.getLong("automation.offline-cap-hours", 12) * 3_600_000L);
        Menu menu = new Menu(3, "<dark_gray>" + r.name() + " Minion " + Text.roman(m.tier));
        Profile owner = plugin.data.profile(m.owner);
        boolean working = canProduce(i, m, r);
        boolean linked = plugin.sellChests.linked(i, Pos.parse(m.pos)) != null;
        long capacity = (long) t.storageStacks() * r.output().getMaxStackSize();
        menu.set(4, Menu.icon(Material.PLAYER_HEAD, "<gold>" + r.name() + " Minion <yellow>" + Text.roman(m.tier), List.of(
                "Owner: <white>" + (owner == null ? "?" : owner.name),
                "Output: <white>1 " + Text.pretty(r.output().name()) + " / " + t.cycleSeconds() + "s",
                "Status: " + (working ? "<green>working" : "<red>idle (requirements not met)"),
                "Delivery: " + (linked ? "<green>linked sell chest" : "<yellow>internal storage"))));
        menu.set(11, Menu.icon(r.output(), "<yellow>Storage", List.of(
                "<white>" + Text.num(m.stored) + "<gray>/" + Text.num(capacity) + " items",
                m.stored >= capacity ? "<red>Full - production stopped" : "",
                "<green>Click to collect")), e -> {
            collect(p, i, m);
            open(p, i, m);
        });
        Settings.MinionTier nt = plugin.settings.minionTiers.get(m.tier + 1);
        List<String> up = new ArrayList<>();
        if (nt == null) up.add("<green>Max tier reached");
        else {
            up.add("Next: tier " + Text.roman(m.tier + 1) + " (" + nt.cycleSeconds() + "s, " + nt.storageStacks() + " stacks)");
            up.add("Cost: " + Text.roses(nt.roses()) + " <gray>+ " + Text.dollars(tierDollars(r, m.tier + 1)));
            up.add("Requires " + r.skill().display + " " + nt.skillLevel());
            up.add("<green>Click to upgrade");
        }
        menu.set(13, Menu.icon(Material.EXPERIENCE_BOTTLE, "<aqua>Upgrade", up), e -> {
            upgrade(p, i, m);
            open(p, i, m);
        });
        menu.set(15, Menu.icon(Material.BARRIER, "<red>Pick up", List.of("Returns the minion item (keeps its tier).")), e -> {
            p.closeInventory();
            pickup(p, i, m);
        });
        menu.fill(Material.GRAY_STAINED_GLASS_PANE);
        menu.open(p);
    }
}
