package dev.skycore.shop;

import dev.skycore.SkyCore;
import dev.skycore.data.Island;
import dev.skycore.data.Profile;
import dev.skycore.items.SpecialItems;
import dev.skycore.skills.Skill;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

/** Sell price calculation: base price x island multiplier x skill bonus x pet x prestige, capped. */
public final class Pricing {
    private final SkyCore plugin;

    public record Sale(double total, long items, Map<Material, Long> sold) {}

    public Pricing(SkyCore plugin) {
        this.plugin = plugin;
    }

    public double base(Material m) {
        return plugin.settings.sellPrice(m);
    }

    public boolean sellable(ItemStack i) {
        return i != null && !i.isEmpty() && base(i.getType()) > 0 && !SpecialItems.isCustom(i);
    }

    public double islandMultiplier(Island i) {
        if (i == null) return 1;
        var u = plugin.settings.sellMultiplier.get(i.sellLevel);
        return u == null ? 1 : u.amount();
    }

    /** Full multiplier for an owner selling items of a skill category on an island. */
    public double multiplier(UUID owner, Island island, Skill skill) {
        Profile p = plugin.data.profile(owner);
        double m = islandMultiplier(island);
        if (p != null) {
            if (skill != null) m *= 1 + plugin.skills.sellBonus(p, skill);
            m *= 1 + plugin.pets.sellBonus(p, skill);
            m *= 1 + p.stars * plugin.settings.config.getDouble("prestige.sell-bonus-per-star", 0.03);
        }
        return Math.min(plugin.settings.config.getDouble("economy.sell-multiplier-cap", 4.0), m);
    }

    public double unitValue(Material m, UUID owner, Island island, boolean bonuses) {
        double b = base(m);
        if (b <= 0) return 0;
        return bonuses ? b * multiplier(owner, island, plugin.settings.priceSkill.get(m)) : b;
    }

    /**
     * Removes every sellable item from the inventory and returns the sale (not yet paid).
     * factor scales the final value (sell wands 90-100%).
     */
    public Sale sellInventory(Inventory inv, UUID owner, Island island, double factor, boolean bonuses) {
        double total = 0;
        long count = 0;
        Map<Material, Long> sold = new EnumMap<>(Material.class);
        Map<Material, Double> unit = new EnumMap<>(Material.class);
        ItemStack[] contents = inv.getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack it = contents[slot];
            if (!sellable(it)) continue;
            double u = unit.computeIfAbsent(it.getType(), m -> unitValue(m, owner, island, bonuses));
            total += u * it.getAmount() * factor;
            count += it.getAmount();
            sold.merge(it.getType(), (long) it.getAmount(), Long::sum);
            inv.setItem(slot, null);
        }
        return new Sale(total, count, sold);
    }

    /** Value of a virtual batch (minions, spawners, chunk hoppers). */
    public double batchValue(Material m, long amount, UUID owner, Island island) {
        return unitValue(m, owner, island, true) * amount;
    }
}
