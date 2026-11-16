package dev.skycore.shop;

import dev.skycore.SkyCore;
import dev.skycore.config.Settings;
import dev.skycore.data.Island;
import dev.skycore.data.Profile;
import dev.skycore.items.SpecialItems;
import dev.skycore.util.Keys;
import dev.skycore.util.Text;
import org.bukkit.Chunk;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;

/** /sell hand|all (base price only) and sell wands (sell-chest pricing x wand factor). */
public final class SellService implements Listener {
    private final SkyCore plugin;
    private final Map<UUID, Long> sellAllCooldown = new HashMap<>();
    private final Map<UUID, Long> wandCooldown = new HashMap<>();

    public SellService(SkyCore plugin) {
        this.plugin = plugin;
    }

    public void paySale(Player p, double total, String source) {
        if (total <= 0) return;
        plugin.eco.deposit(p.getUniqueId(), total, source);
        Profile prof = plugin.data.profile(p.getUniqueId());
        prof.totalSold += total;
        plugin.data.dirty(prof);
    }

    public void sellHand(Player p) {
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (!plugin.pricing.sellable(hand)) {
            Text.msg(p, "<red>That item cannot be sold.");
            return;
        }
        double total = plugin.pricing.base(hand.getType()) * hand.getAmount();
        int amount = hand.getAmount();
        String name = Text.pretty(hand.getType().name());
        p.getInventory().setItemInMainHand(null);
        paySale(p, total, "sell_command");
        Text.msg(p, "Sold <white>" + amount + "x " + name + "</white> for " + Text.dollars(total) + "<gray>.");
    }

    public void sellAll(Player p) {
        long now = System.currentTimeMillis();
        long cd = plugin.settings.config.getLong("economy.sell-all-cooldown-seconds", 30) * 1000L;
        long last = sellAllCooldown.getOrDefault(p.getUniqueId(), 0L);
        if (now - last < cd) {
            Text.msg(p, "<red>/sell all is on cooldown for " + ((cd - (now - last)) / 1000 + 1) + "s.");
            return;
        }
        double total = 0;
        long count = 0;
        ItemStack[] c = p.getInventory().getStorageContents();
        for (int i = 0; i < c.length; i++) {
            if (!plugin.pricing.sellable(c[i])) continue;
            total += plugin.pricing.base(c[i].getType()) * c[i].getAmount();
            count += c[i].getAmount();
            p.getInventory().setItem(i, null);
        }
        if (count == 0) {
            Text.msg(p, "<red>Nothing to sell.");
            return;
        }
        sellAllCooldown.put(p.getUniqueId(), now);
        paySale(p, total, "sell_command");
        Text.msg(p, "Sold <white>" + Text.num(count) + "</white> items for " + Text.dollars(total) + "<gray>. "
                + "<dark_gray>(Sell chests and wands use your island multiplier.)");
    }

    // ------------------------------------------------------------------ wands
    @EventHandler(priority = EventPriority.HIGH)
    public void onUse(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        ItemStack it = e.getItem();
        String type = SpecialItems.type(it);
        if (type == null) return;
        Player p = e.getPlayer();
        boolean right = e.getAction() == Action.RIGHT_CLICK_BLOCK || e.getAction() == Action.RIGHT_CLICK_AIR;
        if (!right) return;
        if (type.equals(SpecialItems.WAND_USES)) {
            e.setCancelled(true);
            redeemCharge(p, it);
            return;
        }
        if (!type.equals(SpecialItems.WAND) || e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null) return;
        e.setCancelled(true);
        useWand(p, e.getClickedBlock(), it);
    }

    private void useWand(Player p, Block target, ItemStack wand) {
        UUID bound = SpecialItems.soulboundTo(wand);
        if (bound != null && !bound.equals(p.getUniqueId())) {
            Text.msg(p, "<red>This wand is soulbound to another player.");
            return;
        }
        Settings.WandTier t = plugin.settings.wandTiers.get(SpecialItems.tier(wand));
        if (t == null) return;
        long uses = SpecialItems.amount(wand);
        if (uses <= 0) {
            Text.msg(p, "<red>This wand has no uses left. <gray>/wand recharge <uses>");
            return;
        }
        long now = System.currentTimeMillis();
        if (now - wandCooldown.getOrDefault(p.getUniqueId(), 0L) < t.cooldownMs()) return;
        if (!(target.getState() instanceof Container)) {
            Text.bar(p, "<red>Right-click a container.");
            return;
        }
        if (!plugin.islands.canBuild(p, target.getLocation())) {
            Text.bar(p, "<red>You can only use wands on islands you are a member of.");
            return;
        }
        wandCooldown.put(p.getUniqueId(), now);
        Island island = plugin.islands.at(target);
        List<Container> containers = new ArrayList<>();
        if (t.radius() <= 0) containers.add((Container) target.getState());
        else {
            int r = t.radius();
            Set<Chunk> chunks = new HashSet<>();
            for (int dx = -r; dx <= r; dx += 16) for (int dz = -r; dz <= r; dz += 16)
                chunks.add(target.getRelative(dx, 0, dz).getChunk());
            chunks.add(target.getRelative(r, 0, r).getChunk());
            chunks.add(target.getRelative(-r, 0, -r).getChunk());
            chunks.add(target.getRelative(r, 0, -r).getChunk());
            chunks.add(target.getRelative(-r, 0, r).getChunk());
            for (Chunk c : chunks)
                for (BlockState s : c.getTileEntities(false)) {
                    if (!(s instanceof Container ct)) continue;
                    if (s.getLocation().distanceSquared(target.getLocation()) > r * r) continue;
                    containers.add(ct);
                }
        }
        double total = 0;
        long items = 0;
        for (Container c : containers) {
            if (plugin.playerShops.isShop(c.getBlock())) continue;
            if (!plugin.islands.canBuild(p, c.getLocation())) continue;
            Pricing.Sale s = plugin.pricing.sellInventory(c.getInventory(), p.getUniqueId(), island, t.factor(), true);
            total += s.total();
            items += s.items();
        }
        if (items == 0) {
            Text.bar(p, "<gray>Nothing to sell there.");
            return;
        }
        setUses(wand, uses - 1);
        plugin.eco.deposit(p.getUniqueId(), total, "wand");
        Profile prof = plugin.data.profile(p.getUniqueId());
        plugin.ranking.ensureWeek(prof);
        prof.totalSold += total;
        prof.weekly.trade += total;
        prof.weekly.reachedAt.put("trade", now);
        plugin.data.dirty(prof);
        Text.bar(p, "<green>Wand sold " + Text.num(items) + " items for " + Text.money(total) + " <gray>(" + (uses - 1) + " uses left)");
    }

    private void setUses(ItemStack wand, long uses) {
        ItemMeta meta = wand.getItemMeta();
        meta.getPersistentDataContainer().set(Keys.AMOUNT, PersistentDataType.LONG, Math.max(0, uses));
        wand.setItemMeta(meta);
        plugin.items.refreshWand(wand);
    }

    private ItemStack findWand(Player p) {
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (SpecialItems.is(hand, SpecialItems.WAND)) return hand;
        for (ItemStack s : p.getInventory().getStorageContents()) if (SpecialItems.is(s, SpecialItems.WAND)) return s;
        return null;
    }

    private void redeemCharge(Player p, ItemStack voucher) {
        ItemStack wand = findWand(p);
        if (wand == null) {
            Text.msg(p, "<red>You need a sell wand in your inventory.");
            return;
        }
        Settings.WandTier t = plugin.settings.wandTiers.get(SpecialItems.tier(wand));
        long add = SpecialItems.amount(voucher);
        long uses = SpecialItems.amount(wand);
        long next = Math.min(t.uses(), uses + add);
        if (next == uses) {
            Text.msg(p, "<yellow>Your wand is already full.");
            return;
        }
        setUses(wand, next);
        voucher.setAmount(voucher.getAmount() - 1);
        Text.msg(p, "<green>Wand recharged to " + next + " uses.");
    }

    public void recharge(Player p, long requested) {
        ItemStack wand = p.getInventory().getItemInMainHand();
        if (!SpecialItems.is(wand, SpecialItems.WAND)) {
            Text.msg(p, "<red>Hold your sell wand.");
            return;
        }
        Settings.WandTier t = plugin.settings.wandTiers.get(SpecialItems.tier(wand));
        long uses = SpecialItems.amount(wand);
        long n = Math.min(requested, t.uses() - uses);
        if (n <= 0) {
            Text.msg(p, "<yellow>Your wand is already full (" + uses + "/" + t.uses() + ").");
            return;
        }
        double cost = n * plugin.settings.automation.getDouble("sell-wands.recharge-cost-per-use", 200);
        if (!plugin.eco.withdraw(p.getUniqueId(), cost, "wand_recharge")) {
            Text.msg(p, "<red>Recharging " + n + " uses costs " + Text.dollars(cost) + "<red>.");
            return;
        }
        setUses(wand, uses + n);
        Text.msg(p, "<green>Recharged " + n + " uses for " + Text.dollars(cost) + "<green>.");
    }
}
