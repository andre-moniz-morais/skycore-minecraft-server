package dev.skycore.market;

import dev.skycore.SkyCore;
import dev.skycore.data.Island;
import dev.skycore.data.PlayerShop;
import dev.skycore.gui.Menu;
import dev.skycore.shop.ShopMenus;
import dev.skycore.util.ItemSerial;
import dev.skycore.util.Pos;
import dev.skycore.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Player chest shops (Dollars). The chest inventory is the stock. Prices are limited to 10%-500% of the
 * base price; a 3% fee is paid by whoever sells. Soulbound items are never accepted.
 */
public final class PlayerShopService {
    private final SkyCore plugin;

    public PlayerShopService(SkyCore plugin) {
        this.plugin = plugin;
    }

    public boolean isShop(Block b) {
        Island i = plugin.islands.at(b);
        return i != null && i.shops.contains(Pos.key(b));
    }

    private double fee() {
        return plugin.settings.config.getDouble("player-shops.fee", 0.03);
    }

    public boolean place(Player p, Island i, Block b) {
        int limit = plugin.settings.islandLevel(i.level).shops();
        if (i.shops.size() >= limit) {
            Text.msg(p, "<red>Shop limit reached (" + limit + "). " + (limit == 0 ? "Shops unlock at island level 2." : "Upgrade your island level."));
            return false;
        }
        PlayerShop s = new PlayerShop();
        s.id = Pos.key(b);
        s.island = i.id;
        s.owner = p.getUniqueId();
        s.pos = s.id;
        plugin.data.shops.put(s.id, s);
        plugin.data.dirty(s);
        i.shops.add(s.id);
        plugin.data.dirty(i);
        Text.msg(p, "<green>Shop chest placed. <gray>Right-click to configure, sneak + right-click to open the stock.");
        return true;
    }

    public void breakShop(Player p, Island i, Block b) {
        PlayerShop s = plugin.data.shops.get(Pos.key(b));
        if (s != null && !s.owner.equals(p.getUniqueId()) && !i.owner.equals(p.getUniqueId())) {
            Text.msg(p, "<red>Only the shop owner can remove it.");
            return;
        }
        if (b.getState() instanceof Chest c) {
            for (ItemStack it : c.getBlockInventory().getContents())
                if (it != null) b.getWorld().dropItemNaturally(b.getLocation().add(0.5, 0.5, 0.5), it);
            c.getBlockInventory().clear();
        }
        i.shops.remove(Pos.key(b));
        plugin.data.dirty(i);
        plugin.data.removeShop(Pos.key(b));
        b.setType(Material.AIR);
        p.getInventory().addItem(plugin.items.shopChest(1)).values().forEach(l -> p.getWorld().dropItemNaturally(p.getLocation(), l));
    }

    private String priceError(PlayerShop s, double price) {
        if (price == 0) return null;
        if (price < 0 || Double.isNaN(price)) return "Invalid price.";
        if (s.material == null) return "Set the item first.";
        double base = plugin.pricing.base(Material.matchMaterial(s.material));
        if (base <= 0) return price >= 1 ? null : "Minimum price is $1.";
        double min = base * plugin.settings.config.getDouble("player-shops.min-price-factor", 0.10);
        double max = base * plugin.settings.config.getDouble("player-shops.max-price-factor", 5.0);
        if (price < min || price > max) return "Price must be between " + Text.money(min) + " and " + Text.money(max) + " per item.";
        return null;
    }

    private ItemStack template(PlayerShop s) {
        return s.item == null ? null : ItemSerial.decode(s.item);
    }

    private Inventory stock(Block b) {
        return b.getState() instanceof Chest c ? c.getBlockInventory() : null;
    }

    private int count(Inventory inv, ItemStack t) {
        int n = 0;
        for (ItemStack it : inv.getContents()) if (it != null && it.isSimilar(t)) n += it.getAmount();
        return n;
    }

    private void remove(Inventory inv, ItemStack t, int amount) {
        ItemStack[] c = inv.getContents();
        for (int i = 0; i < c.length && amount > 0; i++) {
            if (c[i] == null || !c[i].isSimilar(t)) continue;
            int take = Math.min(amount, c[i].getAmount());
            c[i].setAmount(c[i].getAmount() - take);
            amount -= take;
        }
    }

    private int room(Inventory inv, ItemStack t) {
        int r = 0;
        for (ItemStack it : inv.getStorageContents()) {
            if (it == null || it.isEmpty()) r += t.getMaxStackSize();
            else if (it.isSimilar(t)) r += t.getMaxStackSize() - it.getAmount();
        }
        return r;
    }

    // ------------------------------------------------------------------ interaction
    public void interact(Player p, Island i, Block b) {
        PlayerShop s = plugin.data.shops.get(Pos.key(b));
        if (s == null) return;
        if (s.owner.equals(p.getUniqueId())) {
            if (p.isSneaking()) {
                Inventory inv = stock(b);
                if (inv != null) p.openInventory(inv);
            } else settings(p, s, b);
            return;
        }
        if (s.item == null || (s.buyPrice <= 0 && s.sellPrice <= 0)) {
            Text.msg(p, "<gray>This shop is not open yet.");
            return;
        }
        trade(p, s, b);
    }

    private void settings(Player p, PlayerShop s, Block b) {
        Menu m = new Menu(3, "<dark_gray>Shop settings");
        ItemStack t = template(s);
        m.set(10, t != null ? t : Menu.icon(Material.BARRIER, "<red>No item", List.of()), null);
        m.set(11, Menu.icon(Material.ITEM_FRAME, "<yellow>Set item", List.of("Uses the item in your main hand.")), e -> {
            ItemStack hand = p.getInventory().getItemInMainHand();
            if (!AuctionHouse.tradable(hand)) {
                Text.msg(p, "<red>That item can't be sold in a shop.");
                return;
            }
            ItemStack one = hand.clone();
            one.setAmount(1);
            s.item = ItemSerial.encode(one);
            s.material = one.getType().name();
            s.buyPrice = 0;
            s.sellPrice = 0;
            plugin.data.dirty(s);
            settings(p, s, b);
        });
        m.set(13, Menu.icon(Material.EMERALD, "<green>Shop sells for: " + (s.buyPrice > 0 ? Text.money(s.buyPrice) : "<red>off"),
                List.of("Price per item players pay you.", "<yellow>Click to set (0 = off)")), e ->
                plugin.chatInput.ask(p, "Type the price per item the shop SELLS for:", msg -> setPrice(p, s, msg, true)));
        m.set(14, Menu.icon(Material.GOLD_INGOT, "<gold>Shop buys for: " + (s.sellPrice > 0 ? Text.money(s.sellPrice) : "<red>off"),
                List.of("Price per item you pay to players", "(from your own balance).", "<yellow>Click to set (0 = off)")), e ->
                plugin.chatInput.ask(p, "Type the price per item the shop BUYS for:", msg -> setPrice(p, s, msg, false)));
        m.set(16, Menu.icon(Material.CHEST, "<white>Open stock", List.of("Sales so far: <white>" + s.sales)), e -> {
            Inventory inv = stock(b);
            if (inv != null) p.openInventory(inv);
        });
        m.fill(Material.GRAY_STAINED_GLASS_PANE);
        m.open(p);
    }

    private void setPrice(Player p, PlayerShop s, String msg, boolean sells) {
        double v;
        try {
            v = Double.parseDouble(msg.replace(",", "").replace("$", ""));
        } catch (NumberFormatException ex) {
            Text.msg(p, "<red>Not a number.");
            return;
        }
        String err = priceError(s, v);
        if (err != null) {
            Text.msg(p, "<red>" + err);
            return;
        }
        if (sells) s.buyPrice = v;
        else s.sellPrice = v;
        plugin.data.dirty(s);
        Text.msg(p, "<green>Price updated.");
    }

    private void trade(Player p, PlayerShop s, Block b) {
        ItemStack t = template(s);
        Menu m = new Menu(3, "<dark_gray>Shop of " + Text.escape(plugin.islands.ownerName(plugin.islands.get(s.island))));
        Inventory inv = stock(b);
        int stock = inv == null ? 0 : count(inv, t);
        m.set(4, t, null);
        int[] amounts = {1, 16, 64};
        if (s.buyPrice > 0) for (int k = 0; k < 3; k++) {
            int n = amounts[k];
            m.set(10 + k, Menu.icon(Material.LIME_DYE, "<green>Buy " + n, List.of("Cost: " + Text.dollars(s.buyPrice * n), "Stock: <white>" + stock)),
                    e -> {
                        buy(p, s, b, n);
                        trade(p, s, b);
                    });
        }
        if (s.sellPrice > 0) for (int k = 0; k < 3; k++) {
            int n = amounts[k];
            m.set(14 + k, Menu.icon(Material.ORANGE_DYE, "<gold>Sell " + n, List.of("You get: " + Text.dollars(s.sellPrice * n * (1 - fee())),
                    "<dark_gray>(" + Math.round(fee() * 100) + "% fee)")), e -> {
                sell(p, s, b, n);
                trade(p, s, b);
            });
        }
        m.fill(Material.GRAY_STAINED_GLASS_PANE);
        m.open(p);
    }

    private void buy(Player p, PlayerShop s, Block b, int n) {
        ItemStack t = template(s);
        Inventory inv = stock(b);
        if (inv == null || count(inv, t) < n) {
            Text.msg(p, "<red>Not enough stock.");
            return;
        }
        ItemStack give = t.clone();
        give.setAmount(n);
        if (!ShopMenus.fits(p, give)) {
            Text.msg(p, "<red>Not enough inventory space.");
            return;
        }
        double cost = s.buyPrice * n;
        if (!plugin.eco.transfer(p.getUniqueId(), s.owner, cost, cost * fee(), "shop")) {
            Text.msg(p, "<red>You need " + Text.dollars(cost) + "<red>.");
            return;
        }
        remove(inv, t, n);
        p.getInventory().addItem(give);
        sold(s, p, n, cost, true);
    }

    private void sell(Player p, PlayerShop s, Block b, int n) {
        ItemStack t = template(s);
        Inventory inv = stock(b);
        if (count(p.getInventory(), t) < n) {
            Text.msg(p, "<red>You don't have " + n + " of that item.");
            return;
        }
        if (inv == null || room(inv, t) < n) {
            Text.msg(p, "<red>The shop is full.");
            return;
        }
        double pay = s.sellPrice * n;
        if (!plugin.eco.transfer(s.owner, p.getUniqueId(), pay, pay * fee(), "shop")) {
            Text.msg(p, "<red>The shop owner can't afford that right now.");
            return;
        }
        remove(p.getInventory(), t, n);
        ItemStack put = t.clone();
        put.setAmount(n);
        inv.addItem(put);
        sold(s, p, n, pay, false);
    }

    private void sold(PlayerShop s, Player customer, int n, double value, boolean shopSold) {
        s.sales++;
        plugin.data.dirty(s);
        Island i = plugin.islands.get(s.island);
        if (i != null) plugin.islandMissions.progress(i, "shop_sales", 1);
        Text.msg(customer, "<green>" + (shopSold ? "Bought " : "Sold ") + n + "x " + Text.pretty(s.material) + " for " + Text.money(value) + ".");
        Player o = Bukkit.getPlayer(s.owner);
        if (o != null) Text.bar(o, "<green>Shop: " + customer.getName() + (shopSold ? " bought " : " sold you ") + n + "x " + Text.pretty(s.material));
    }

    // ------------------------------------------------------------------ /shops <item>
    public void find(Player p, String query) {
        Material m = Material.matchMaterial(query);
        if (m == null) {
            Text.msg(p, "<red>Unknown item: " + Text.escape(query));
            return;
        }
        List<PlayerShop> list = new ArrayList<>();
        for (PlayerShop s : plugin.data.shops.values())
            if (m.name().equals(s.material) && s.buyPrice > 0 && plugin.islands.get(s.island) != null) list.add(s);
        list.sort(Comparator.comparingDouble(s -> s.buyPrice));
        Menu menu = new Menu(3, "<dark_gray>Shops selling " + Text.pretty(m.name()));
        for (int k = 0; k < Math.min(27, list.size()); k++) {
            PlayerShop s = list.get(k);
            Island i = plugin.islands.get(s.island);
            Block b = Pos.parse(s.pos).block();
            int stock = b != null && Pos.parse(s.pos).isLoaded() && stock(b) != null ? count(stock(b), template(s)) : -1;
            menu.set(k, Menu.icon(m, "<white>" + Text.escape(plugin.islands.ownerName(i)) + "'s shop", List.of(
                    "Price: " + Text.dollars(s.buyPrice) + " <gray>each",
                    stock >= 0 ? "Stock: <white>" + stock : "Stock: <gray>unknown",
                    "<green>Click to teleport")), e -> {
                p.closeInventory();
                Location warp = i.shopWarp != null ? i.shopWarp.toLocation() : plugin.islands.home(i);
                plugin.islands.teleport(p, warp);
            });
        }
        if (list.isEmpty()) Text.msg(p, "<gray>No shops are selling " + Text.pretty(m.name()) + " right now.");
        else menu.open(p);
    }
}
