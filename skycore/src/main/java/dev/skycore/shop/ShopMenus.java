package dev.skycore.shop;

import dev.skycore.SkyCore;
import dev.skycore.config.Settings;
import dev.skycore.data.Profile;
import dev.skycore.gui.Menu;
import dev.skycore.skills.Skill;
import dev.skycore.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** /shop: admin shop (Dollars) and the special shop (Roses + Dollars items). */
public final class ShopMenus {
    private final SkyCore plugin;

    public ShopMenus(SkyCore plugin) {
        this.plugin = plugin;
    }

    private double buyFactor() {
        return plugin.settings.config.getDouble("economy.admin-shop-buy-factor", 4.0);
    }

    public void main(Player p) {
        Menu m = new Menu(4, "<dark_gray>Server Shop");
        int slot = 10;
        for (Settings.PriceGroup g : plugin.settings.priceGroups) {
            m.set(slot++, Menu.icon(g.icon(), "<yellow>" + g.name(), List.of("Buy at " + Text.num(buyFactor()) + "x, sell at base price.", "<green>Click to open")),
                    e -> category(p, g.name(), g.items()));
        }
        m.set(slot++, Menu.icon(Material.CRAFTING_TABLE, "<yellow>Utility", List.of("Blocks and tools to get started.", "<green>Click to open")),
                e -> buyOnly(p, "Utility", plugin.settings.buyOnly));
        m.set(slot, Menu.icon(Material.OAK_SAPLING, "<yellow>Plants & Seeds", List.of("Saplings, seeds, cactus, bamboo...",
                        "<gray>Carrots, potatoes, sugar cane and", "<gray>nether wart are in the Farming page.", "<green>Click to open")),
                e -> buyOnly(p, "Plants & Seeds", plugin.settings.plants));
        m.set(19, Menu.icon(Material.CHEST, "<green>Chests & Hoppers", List.of("Sell chests, shop chests, chunk hoppers.")), e -> chests(p));
        m.set(20, Menu.icon(Material.PLAYER_HEAD, "<gold>Minions", List.of("Virtual minions that produce for you.")), e -> minions(p, 0));
        m.set(21, Menu.icon(Material.WHEAT_SEEDS, "<yellow>Special Seeds", List.of("Permanent crops that never need replanting.")), e -> seeds(p));
        m.set(22, Menu.icon(Material.BLAZE_ROD, "<gold>Sell Wands", List.of("Sell a container's contents with one click.")), e -> wands(p));
        m.set(23, Menu.icon(Material.SPAWNER, "<red>Spawners", List.of("Unlock types with Roses, buy units with Dollars.")), e -> spawners(p));
        m.set(24, Menu.icon(Material.EGG, "<light_purple>Pets", List.of("Efficiency pets, bought with Roses only.")), e -> plugin.pets.shop(p));
        Profile prof = plugin.data.profile(p.getUniqueId());
        m.set(31, Menu.icon(Material.SUNFLOWER, "<white>Your balance", List.of(Text.dollars(prof.dollars), Text.roses(prof.roses))));
        m.fill(Material.BLACK_STAINED_GLASS_PANE);
        m.open(p);
    }

    // ------------------------------------------------------------------ admin shop
    private void category(Player p, String name, Map<Material, Double> items) {
        Menu m = new Menu(6, "<dark_gray>" + name);
        int slot = 0;
        for (Map.Entry<Material, Double> en : items.entrySet()) {
            if (slot >= 45) break;
            Material mat = en.getKey();
            double sell = en.getValue();
            // an explicit shop price (Plants & Seeds / Utility) wins over the sell price x buy factor
            Double fixed = plugin.settings.plants.getOrDefault(mat, plugin.settings.buyOnly.get(mat));
            double buy = fixed != null ? fixed : sell * buyFactor();
            m.set(slot++, Menu.icon(mat, "<white>" + Text.pretty(mat.name()), List.of(
                    "Buy: " + Text.dollars(buy) + " <gray>each",
                    "Sell: " + Text.dollars(sell) + " <gray>each",
                    "",
                    "<yellow>Left</yellow>: buy 1   <yellow>Shift+Left</yellow>: buy 64",
                    "<yellow>Right</yellow>: sell 1   <yellow>Shift+Right</yellow>: sell all")), e -> {
                ClickType c = e.getClick();
                if (c.isLeftClick()) buy(p, mat, c.isShiftClick() ? 64 : 1, buy);
                else if (c.isRightClick()) sell(p, mat, c.isShiftClick() ? Integer.MAX_VALUE : 1);
            });
        }
        m.set(49, Menu.back(), e -> main(p));
        m.open(p);
    }

    private void buyOnly(Player p, String title, Map<Material, Double> items) {
        Menu m = new Menu(6, "<dark_gray>" + title);
        int slot = 0;
        for (Map.Entry<Material, Double> en : items.entrySet()) {
            if (slot >= 45) break;
            Material mat = en.getKey();
            double price = en.getValue();
            m.set(slot++, Menu.icon(mat, "<white>" + Text.pretty(mat.name()), List.of("Buy: " + Text.dollars(price),
                    "", "<yellow>Left</yellow>: buy 1   <yellow>Shift+Left</yellow>: buy 16")), e -> {
                if (e.getClick().isLeftClick()) buy(p, mat, e.getClick().isShiftClick() ? Math.min(16, mat.getMaxStackSize()) : 1, price);
            });
        }
        m.set(49, Menu.back(), e -> main(p));
        m.open(p);
    }

    private void buy(Player p, Material mat, int amount, double unit) {
        double cost = unit * amount;
        ItemStack it = new ItemStack(mat, amount);
        if (!fits(p, it)) {
            Text.msg(p, "<red>Not enough inventory space.");
            return;
        }
        if (!plugin.eco.withdraw(p.getUniqueId(), cost, "adminshop_buy")) {
            Text.msg(p, "<red>You need " + Text.dollars(cost) + "<red>.");
            return;
        }
        p.getInventory().addItem(it);
        Text.bar(p, "<green>Bought " + amount + "x " + Text.pretty(mat.name()) + " for " + Text.money(cost));
    }

    private void sell(Player p, Material mat, int max) {
        double unit = plugin.pricing.base(mat);
        int sold = 0;
        ItemStack[] c = p.getInventory().getStorageContents();
        for (int i = 0; i < c.length && sold < max; i++) {
            ItemStack s = c[i];
            if (s == null || s.getType() != mat || !plugin.pricing.sellable(s)) continue;
            int take = Math.min(s.getAmount(), max - sold);
            s.setAmount(s.getAmount() - take);
            sold += take;
        }
        if (sold == 0) {
            Text.bar(p, "<red>You have none to sell.");
            return;
        }
        double total = unit * sold;
        plugin.sell.paySale(p, total, "adminshop_sell");
        Text.bar(p, "<green>Sold " + sold + "x " + Text.pretty(mat.name()) + " for " + Text.money(total));
    }

    public static boolean fits(Player p, ItemStack it) {
        int room = 0;
        for (ItemStack s : p.getInventory().getStorageContents()) {
            if (s == null || s.isEmpty()) room += it.getMaxStackSize();
            else if (s.isSimilar(it)) room += s.getMaxStackSize() - s.getAmount();
            if (room >= it.getAmount()) return true;
        }
        return false;
    }

    private boolean pay(Player p, long roses, double dollars, String source) {
        if (!plugin.eco.charge(p.getUniqueId(), roses, dollars, source)) {
            Text.msg(p, "<red>You need " + (roses > 0 ? Text.roses(roses) + " <red>and " : "") + Text.dollars(dollars) + "<red>.");
            return false;
        }
        return true;
    }

    private void giveBought(Player p, ItemStack it) {
        p.getInventory().addItem(it).values().forEach(l -> plugin.rewards.giveOrBox(p.getUniqueId(), l));
        Text.msg(p, "<green>Purchased " + Text.plain(it.getItemMeta().displayName()) + ".");
    }

    // ------------------------------------------------------------------ special items
    private void chests(Player p) {
        Menu m = new Menu(3, "<dark_gray>Chests & Hoppers");
        double sc = plugin.settings.automation.getDouble("sell-chest.dollars", 5000);
        double shop = plugin.settings.automation.getDouble("shop-chest.dollars", 10000);
        long chR = plugin.settings.automation.getLong("chunk-hopper.roses", 40);
        double chD = plugin.settings.automation.getDouble("chunk-hopper.dollars", 25000);
        m.set(11, Menu.icon(Material.CHEST, "<green>Sell Chest", List.of("Price: " + Text.dollars(sc), "Tradable.", "<green>Click to buy")), e -> {
            if (pay(p, 0, sc, "buy_sellchest")) giveBought(p, plugin.items.sellChest(1));
        });
        m.set(13, Menu.icon(Material.CHEST, "<aqua>Shop Chest", List.of("Price: " + Text.dollars(shop), "Tradable.", "<green>Click to buy")), e -> {
            if (pay(p, 0, shop, "buy_shopchest")) giveBought(p, plugin.items.shopChest(1));
        });
        m.set(15, Menu.icon(Material.HOPPER, "<light_purple>Chunk Hopper", List.of("Price: " + Text.roses(chR) + " <gray>+ " + Text.dollars(chD), "Soulbound.", "<green>Click to buy")), e -> {
            if (pay(p, chR, chD, "buy_chunkhopper")) giveBought(p, plugin.items.chunkHopper(p.getUniqueId(), 1));
        });
        m.set(22, Menu.back(), e -> main(p));
        m.fill(Material.GRAY_STAINED_GLASS_PANE);
        m.open(p);
    }

    /** Minion purchase list. voucherTier > 0 means the player is redeeming a voucher (free, that tier). */
    public void minions(Player p, int voucherTier) {
        Menu m = new Menu(4, voucherTier > 0 ? "<dark_gray>Choose a minion (voucher)" : "<dark_gray>Minions");
        Settings.MinionTier t1 = plugin.settings.minionTiers.get(1);
        int slot = 0;
        for (Settings.MinionResource r : plugin.settings.minionResources.values()) {
            if (slot >= 27) break;
            String req = plugin.minions.buyRequirement(p.getUniqueId(), r);
            double dollars = plugin.minions.tierDollars(r, 1);
            List<String> lore = new ArrayList<>();
            lore.add("Skill: <white>" + r.skill().display);
            lore.add("Produces: <white>" + Text.pretty(r.output().name()));
            if (voucherTier == 0) lore.add("Price: " + Text.roses(t1.roses()) + " <gray>+ " + Text.dollars(dollars));
            else lore.add("Voucher: <white>tier " + Text.roman(voucherTier));
            lore.add(req == null ? "<green>Click to " + (voucherTier > 0 ? "redeem" : "buy") : "<red>Locked: " + req);
            m.set(slot++, Menu.icon(r.output(), "<gold>" + r.name() + " Minion", lore), e -> {
                if (req != null) {
                    Text.msg(p, "<red>Locked: " + req);
                    return;
                }
                if (voucherTier > 0) {
                    if (!plugin.rewards.consumeVoucher(p, voucherTier)) return;
                    giveBought(p, plugin.items.minion(p.getUniqueId(), r.id(), voucherTier));
                    p.closeInventory();
                    return;
                }
                if (pay(p, t1.roses(), dollars, "buy_minion")) giveBought(p, plugin.items.minion(p.getUniqueId(), r.id(), 1));
            });
        }
        if (voucherTier == 0) m.set(31, Menu.back(), e -> main(p));
        m.open(p);
    }

    private void seeds(Player p) {
        Menu m = new Menu(3, "<dark_gray>Special Seeds");
        Profile prof = plugin.data.profile(p.getUniqueId());
        int farming = plugin.skills.level(prof, Skill.FARMING);
        int slot = 0;
        int pack = plugin.settings.automation.getInt("special-crops.pack-size", 16);
        for (Settings.CropType c : plugin.settings.crops.values()) {
            boolean ok = farming >= c.farming();
            m.set(slot++, Menu.icon(c.seed(), "<yellow>Special " + c.name() + " <gray>(pack of " + pack + ")", List.of(
                    "Drop per harvest: <white>" + c.amount() + "x " + Text.pretty(c.drop().name()),
                    "Price: " + Text.roses(c.roses()) + " <gray>+ " + Text.dollars(c.dollars()),
                    c.netherOnly() ? "<gold>Nether island only" : "",
                    ok ? "<green>Click to buy" : "<red>Requires Farming " + c.farming())), e -> {
                if (!ok) return;
                if (pay(p, c.roses(), c.dollars(), "buy_seeds")) giveBought(p, plugin.items.seedPack(p.getUniqueId(), c.id(), 1));
            });
        }
        m.set(22, Menu.back(), e -> main(p));
        m.open(p);
    }

    private void wands(Player p) {
        Menu m = new Menu(3, "<dark_gray>Sell Wands");
        int slot = 11;
        for (Settings.WandTier t : plugin.settings.wandTiers.values()) {
            m.set(slot, Menu.icon(Material.BLAZE_ROD, "<gold>Sell Wand " + Text.roman(t.tier()), List.of(
                    "Uses: <white>" + t.uses(), "Cooldown: <white>" + (t.cooldownMs() / 1000.0) + "s",
                    "Range: <white>" + (t.radius() > 0 ? "containers within " + t.radius() + " blocks" : "1 container"),
                    "Value: <white>" + Math.round(t.factor() * 100) + "% <gray>of a sell chest",
                    "Price: " + Text.roses(t.roses()) + " <gray>+ " + Text.dollars(t.dollars()),
                    "<green>Click to buy"), true), e -> {
                if (pay(p, t.roses(), t.dollars(), "buy_wand")) giveBought(p, plugin.items.wand(p.getUniqueId(), t.tier(), t.uses()));
            });
            slot += 2;
        }
        m.set(22, Menu.back(), e -> main(p));
        m.open(p);
    }

    private void spawners(Player p) {
        Menu m = new Menu(4, "<dark_gray>Spawners");
        Profile prof = plugin.data.profile(p.getUniqueId());
        int combat = plugin.skills.level(prof, Skill.COMBAT);
        int slot = 0;
        for (Settings.SpawnerType t : plugin.settings.spawnerTypes.values()) {
            boolean unlocked = prof.unlockedSpawners.contains(t.id());
            List<String> lore = new ArrayList<>();
            lore.add("Dimension: <white>" + t.dimension().display);
            lore.add("Unit price: " + Text.dollars(t.dollars()));
            if (unlocked) {
                lore.add("<green>Type unlocked");
                lore.add("<yellow>Left</yellow>: buy 1 unit   <yellow>Shift+Left</yellow>: buy 10");
            } else {
                lore.add("Unlock: " + Text.roses(t.roses()) + " <gray>(once per account)");
                lore.add(combat >= t.combat() ? "<green>Click to unlock" : "<red>Requires Combat " + t.combat());
            }
            m.set(slot++, Menu.icon(Material.SPAWNER, "<yellow>" + t.name(), lore), e -> {
                if (!unlocked) {
                    if (combat < t.combat()) return;
                    if (!plugin.eco.takeRoses(p.getUniqueId(), t.roses(), "spawner_unlock")) {
                        Text.msg(p, "<red>You need " + Text.roses(t.roses()) + "<red>.");
                        return;
                    }
                    prof.unlockedSpawners.add(t.id());
                    plugin.data.dirty(prof);
                    Text.msg(p, "<green>" + t.name() + " spawners unlocked for your account.");
                    spawners(p);
                    return;
                }
                int n = e.getClick().isShiftClick() ? 10 : 1;
                if (pay(p, 0, t.dollars() * n, "buy_spawner")) giveBought(p, plugin.items.spawner(t.id(), n));
            });
        }
        m.set(31, Menu.back(), e -> main(p));
        m.open(p);
    }
}
