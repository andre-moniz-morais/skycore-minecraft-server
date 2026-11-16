package dev.skycore.market;

import dev.skycore.SkyCore;
import dev.skycore.config.Settings;
import dev.skycore.data.Listing;
import dev.skycore.data.Profile;
import dev.skycore.gui.Menu;
import dev.skycore.items.SpecialItems;
import dev.skycore.shop.ShopMenus;
import dev.skycore.util.ItemSerial;
import dev.skycore.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;

/**
 * /ah - Dollars only. 2% listing fee (non refundable), 5% sale tax, 48 h listings, minimum price 50% of base,
 * soulbound items can never be listed. Repeated cheap trades between two accounts alert staff.
 */
public final class AuctionHouse {
    private static final Set<String> TRADABLE_SPECIAL = Set.of(SpecialItems.SPAWNER, SpecialItems.SELL_CHEST, SpecialItems.SHOP_CHEST);
    private final SkyCore plugin;
    private final Map<String, List<Long>> pairTrades = new HashMap<>();

    public AuctionHouse(SkyCore plugin) {
        this.plugin = plugin;
    }

    private double cfg(String k, double d) {
        return plugin.settings.config.getDouble("auction." + k, d);
    }

    /** Reference ("base") value of a stack for minimum price and abuse checks. */
    public double baseValue(ItemStack it) {
        String t = SpecialItems.type(it);
        if (SpecialItems.SPAWNER.equals(t)) {
            Settings.SpawnerType st = plugin.settings.spawnerTypes.get(SpecialItems.data(it));
            return st == null ? 0 : st.dollars() * Math.max(1, SpecialItems.amount(it)) * it.getAmount();
        }
        if (SpecialItems.SELL_CHEST.equals(t)) return plugin.settings.automation.getDouble("sell-chest.dollars", 5000) * it.getAmount();
        if (SpecialItems.SHOP_CHEST.equals(t)) return plugin.settings.automation.getDouble("shop-chest.dollars", 10000) * it.getAmount();
        return plugin.pricing.base(it.getType()) * it.getAmount();
    }

    public static boolean tradable(ItemStack it) {
        if (it == null || it.isEmpty() || SpecialItems.isSoulbound(it)) return false;
        String t = SpecialItems.type(it);
        return t == null || TRADABLE_SPECIAL.contains(t);
    }

    public int maxListings(UUID u) {
        return plugin.settings.config.getInt("auction.base-listings", 5) + plugin.islands.highestLevel(u);
    }

    public List<Listing> of(UUID u) {
        List<Listing> out = new ArrayList<>();
        for (Listing l : plugin.data.listings.values()) if (l.seller.equals(u)) out.add(l);
        return out;
    }

    public void list(Player p, double price) {
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (!tradable(hand)) {
            Text.msg(p, "<red>That item cannot be listed (soulbound items and Roses purchases are never tradable).");
            return;
        }
        if (Double.isNaN(price) || price <= 0) {
            Text.msg(p, "<red>Invalid price.");
            return;
        }
        double min = Math.max(1, baseValue(hand) * cfg("min-price-factor", 0.5));
        if (price < min) {
            Text.msg(p, "<red>Minimum price for this listing is " + Text.dollars(min) + "<red>.");
            return;
        }
        if (of(p.getUniqueId()).size() >= maxListings(p.getUniqueId())) {
            Text.msg(p, "<red>You can have at most " + maxListings(p.getUniqueId()) + " listings.");
            return;
        }
        double fee = price * cfg("listing-fee", 0.02);
        if (!plugin.eco.withdraw(p.getUniqueId(), fee, "ah_listing_fee")) {
            Text.msg(p, "<red>The listing fee is " + Text.dollars(fee) + "<red>.");
            return;
        }
        Listing l = new Listing();
        l.id = UUID.randomUUID().toString().substring(0, 12);
        l.seller = p.getUniqueId();
        l.sellerName = p.getName();
        l.item = ItemSerial.encode(hand);
        l.material = hand.getType().name();
        l.amount = hand.getAmount();
        l.price = price;
        l.created = System.currentTimeMillis();
        l.expires = l.created + (long) (cfg("duration-hours", 48) * 3_600_000L);
        p.getInventory().setItemInMainHand(null);
        plugin.data.listings.put(l.id, l);
        plugin.data.dirty(l);
        Text.msg(p, "<green>Listed for " + Text.dollars(price) + "<green>. Fee paid: " + Text.money(fee) + ".");
    }

    public void buy(Player p, Listing l) {
        if (!plugin.data.listings.containsKey(l.id)) {
            Text.msg(p, "<red>That listing is gone.");
            return;
        }
        if (l.seller.equals(p.getUniqueId())) {
            Text.msg(p, "<red>That's your own listing.");
            return;
        }
        ItemStack item = ItemSerial.decode(l.item);
        if (!ShopMenus.fits(p, item)) {
            Text.msg(p, "<red>Not enough inventory space.");
            return;
        }
        double tax = l.price * cfg("sale-tax", 0.05);
        if (!plugin.eco.transfer(p.getUniqueId(), l.seller, l.price, tax, "ah")) {
            Text.msg(p, "<red>You need " + Text.dollars(l.price) + "<red>.");
            return;
        }
        plugin.data.removeListing(l.id);
        p.getInventory().addItem(item);
        Text.msg(p, "<green>Bought " + l.amount + "x " + Text.pretty(l.material) + " for " + Text.dollars(l.price) + "<green>.");
        Player s = Bukkit.getPlayer(l.seller);
        if (s != null) Text.msg(s, "<green>Your " + l.amount + "x " + Text.pretty(l.material) + " sold for " + Text.dollars(l.price - tax) + " <gray>(after 5% tax).");
        plugin.data.audit("ah_sale", l.seller + "->" + p.getUniqueId() + " " + l.amount + "x" + l.material + " $" + l.price);
        checkSuspicious(p, l, item);
    }

    private void checkSuspicious(Player buyer, Listing l, ItemStack item) {
        double base = baseValue(item);
        if (base <= 0 || l.price >= base * cfg("suspicious-price-factor", 0.10)) return;
        String a = l.seller.toString(), b = buyer.getUniqueId().toString();
        String key = a.compareTo(b) < 0 ? a + b : b + a;
        long now = System.currentTimeMillis();
        List<Long> times = pairTrades.computeIfAbsent(key, k -> new ArrayList<>());
        times.removeIf(t -> now - t > 86_400_000L);
        times.add(now);
        if (times.size() >= plugin.settings.config.getInt("auction.suspicious-repeat-count", 3))
            plugin.alerts.staff("ah", "Repeated cheap /ah trades between " + l.sellerName + " and " + buyer.getName()
                    + " (" + times.size() + " in 24h, last " + Text.money(l.price) + " vs base " + Text.money(base) + ")");
    }

    public void cancel(Player p, Listing l) {
        if (!l.seller.equals(p.getUniqueId()) || !plugin.data.listings.containsKey(l.id)) return;
        plugin.data.removeListing(l.id);
        plugin.rewards.giveOrBox(l.seller, ItemSerial.decode(l.item));
        Text.msg(p, "Listing cancelled. The listing fee is not refunded.");
    }

    public void expire() {
        long now = System.currentTimeMillis();
        for (Listing l : new ArrayList<>(plugin.data.listings.values())) {
            if (l.expires > now) continue;
            plugin.data.removeListing(l.id);
            Profile seller = plugin.data.profile(l.seller);
            if (seller == null) continue;
            seller.rewardBox.add(l.item);
            plugin.data.dirty(seller);
            Player sp = Bukkit.getPlayer(l.seller);
            if (sp != null) Text.msg(sp, "<yellow>Your listing of " + l.amount + "x " + Text.pretty(l.material) + " expired. Collect it with /rewards.");
        }
    }

    private String category(Listing l) {
        Material m = Material.matchMaterial(l.material);
        if (m == Material.SPAWNER) return "spawners";
        for (Settings.PriceGroup g : plugin.settings.priceGroups) if (m != null && g.items().containsKey(m)) return g.id();
        return "other";
    }

    // ------------------------------------------------------------------ menus
    public void open(Player p, int page, String category, String search) {
        List<Listing> all = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (Listing l : plugin.data.listings.values()) {
            if (l.expires <= now) continue;
            if (category != null && !category.equals(category(l))) continue;
            if (search != null && !l.material.toLowerCase().contains(search.toLowerCase().replace(' ', '_'))) continue;
            all.add(l);
        }
        all.sort(Comparator.comparingDouble(l -> l.price / Math.max(1, l.amount)));
        int pages = Math.max(1, (all.size() + 44) / 45);
        int pg = Math.max(0, Math.min(page, pages - 1));
        Menu m = new Menu(6, "<dark_gray>Auction House <gray>(" + (pg + 1) + "/" + pages + ")");
        for (int i = 0; i < 45 && pg * 45 + i < all.size(); i++) {
            Listing l = all.get(pg * 45 + i);
            ItemStack shown = ItemSerial.decode(l.item);
            ItemMeta meta = shown.getItemMeta();
            List<net.kyori.adventure.text.Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
            lore.add(Text.item(""));
            lore.add(Text.item("<gray>Price: " + Text.dollars(l.price) + " <dark_gray>(" + Text.money(l.price / Math.max(1, l.amount)) + " each)"));
            lore.add(Text.item("<gray>Seller: <white>" + Text.escape(l.sellerName)));
            lore.add(Text.item("<gray>Expires in: <white>" + Text.duration((l.expires - now) / 1000)));
            lore.add(Text.item(l.seller.equals(p.getUniqueId()) ? "<red>Click to cancel" : "<green>Click to buy"));
            meta.lore(lore);
            shown.setItemMeta(meta);
            m.set(i, shown, e -> {
                if (l.seller.equals(p.getUniqueId())) cancel(p, l);
                else buy(p, l);
                open(p, pg, category, search);
            });
        }
        if (pg > 0) m.set(45, Menu.icon(Material.ARROW, "<yellow>Previous page", List.of()), e -> open(p, pg - 1, category, search));
        if (pg < pages - 1) m.set(53, Menu.icon(Material.ARROW, "<yellow>Next page", List.of()), e -> open(p, pg + 1, category, search));
        int slot = 46;
        m.set(slot++, Menu.icon(Material.COMPASS, "<white>All", List.of()), e -> open(p, 0, null, null));
        for (Settings.PriceGroup g : plugin.settings.priceGroups) {
            if (slot > 50) break;
            m.set(slot++, Menu.icon(g.icon(), "<white>" + g.name(), List.of()), e -> open(p, 0, g.id(), null));
        }
        m.set(51, Menu.icon(Material.SPAWNER, "<white>Spawners", List.of()), e -> open(p, 0, "spawners", null));
        m.set(52, Menu.icon(Material.NAME_TAG, "<white>Search / sell", List.of("<gray>/ah search <name>", "<gray>/ah sell <price> <dark_gray>(item in hand)",
                "<gray>Your listings: <white>" + of(p.getUniqueId()).size() + "/" + maxListings(p.getUniqueId()))), null);
        m.open(p);
    }
}
