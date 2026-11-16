package dev.skycore.goals;

import dev.skycore.SkyCore;
import dev.skycore.config.Settings;
import dev.skycore.data.Profile;
import dev.skycore.gui.Menu;
import dev.skycore.items.SpecialItems;
import dev.skycore.shop.ShopMenus;
import dev.skycore.skills.Skill;
import dev.skycore.util.ItemSerial;
import dev.skycore.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Grants reward strings (missions, tiers, rankings) and keeps the /rewards claim box for items
 * that could not be delivered. Rewards never include Roses.
 */
public final class RewardService {
    private final SkyCore plugin;

    public RewardService(SkyCore plugin) {
        this.plugin = plugin;
    }

    private boolean boxOnly;

    /** Grants a reward but always puts items in the /rewards box (weekly ranking). */
    public void grantToBox(UUID u, String r, String context) {
        boxOnly = true;
        try {
            grant(u, r, context);
        } finally {
            boxOnly = false;
        }
    }

    public void giveOrBox(UUID u, ItemStack it) {
        if (it == null || it.isEmpty()) return;
        Player p = Bukkit.getPlayer(u);
        if (!boxOnly && p != null && ShopMenus.fits(p, it)) {
            p.getInventory().addItem(it);
            return;
        }
        Profile prof = plugin.data.profile(u);
        if (prof == null) return;
        prof.rewardBox.add(ItemSerial.encode(it));
        plugin.data.dirty(prof);
        if (p != null) Text.msg(p, "<yellow>An item was sent to your reward box: <white>/rewards");
    }

    public static boolean isItemReward(String r) {
        return !r.startsWith("dollars:");
    }

    public String describe(String r) {
        String[] a = r.split(":");
        return switch (a[0]) {
            case "dollars" -> Text.dollars(Double.parseDouble(a[1]));
            case "chunk_hopper" -> a[1] + "x Chunk Hopper";
            case "seed_pack" -> a[2] + "x " + (a[1].equals("random") ? "random" : Text.pretty(a[1])) + " seed pack";
            case "wand" -> "Sell Wand " + Text.roman(Integer.parseInt(a[1]));
            case "wand_uses" -> a[1] + " sell wand uses";
            case "pet_egg" -> Text.pretty(a[1]) + " pet egg";
            case "minion_voucher" -> "Minion voucher (tier " + Text.roman(Integer.parseInt(a[1])) + ")";
            case "title" -> "Title " + a[1] + "<reset>";
            case "pet_slot" -> "2nd pet slot";
            case "guide" -> "Starter guide";
            default -> r;
        };
    }

    public void grant(UUID u, String r, String context) {
        Profile prof = plugin.data.profile(u);
        if (prof == null) return;
        String[] a = r.split(":");
        switch (a[0]) {
            case "dollars" -> plugin.eco.deposit(u, Double.parseDouble(a[1]), "reward:" + context);
            case "chunk_hopper" -> giveOrBox(u, plugin.items.chunkHopper(u, Integer.parseInt(a[1])));
            case "seed_pack" -> {
                int n = Integer.parseInt(a[2]);
                for (int k = 0; k < n; k++) giveOrBox(u, plugin.items.seedPack(u, a[1].equals("random") ? randomCrop(prof) : a[1], 1));
            }
            case "wand" -> {
                int tier = Integer.parseInt(a[1]);
                Settings.WandTier t = plugin.settings.wandTiers.get(tier);
                if (t != null) giveOrBox(u, plugin.items.wand(u, tier, t.uses()));
            }
            case "wand_uses" -> giveOrBox(u, plugin.items.wandUses(u, Long.parseLong(a[1])));
            case "pet_egg" -> giveOrBox(u, plugin.items.petEgg(u, a[1]));
            case "minion_voucher" -> giveOrBox(u, plugin.items.minionVoucher(u, Integer.parseInt(a[1])));
            case "title" -> {
                String title = a[1];
                prof.titles.add(title);
                if (a.length > 2) prof.titleExpiry.put(title, System.currentTimeMillis() + Long.parseLong(a[2]) * 86_400_000L);
                if (prof.activeTitle == null) prof.activeTitle = title;
            }
            case "pet_slot" -> prof.petSlot2 = true;
            case "guide" -> {
                Player p = Bukkit.getPlayer(u);
                if (p != null) guide(p);
            }
            default -> plugin.getLogger().warning("Unknown reward: " + r);
        }
        plugin.data.dirty(prof);
    }

    private String randomCrop(Profile prof) {
        int farming = plugin.skills.level(prof, Skill.FARMING);
        List<String> ok = new ArrayList<>();
        for (Settings.CropType c : plugin.settings.crops.values())
            if (c.farming() <= farming && !c.netherOnly()) ok.add(c.id());
        if (ok.isEmpty()) ok.add(plugin.settings.crops.keySet().iterator().next());
        return ok.get(ThreadLocalRandom.current().nextInt(ok.size()));
    }

    public void guide(Player p) {
        Text.raw(p, "<gradient:#ff5e8a:#ffb86c><bold>SkyCore - Starter Guide</bold></gradient>");
        Text.raw(p, "<gray>1. <white>Mine</white> your generator, <white>harvest</white> crops, <white>fish</white> and <white>fight</white> to level skills.");
        Text.raw(p, "<gray>2. Every few actions you earn <#ff5e8a>Roses ✿</#ff5e8a> - the scarce currency. They can never be traded.");
        Text.raw(p, "<gray>3. Sell items for <green>Dollars</green>: <white>/sell all</white>, or better, place a <white>Sell Chest</white> (/shop).");
        Text.raw(p, "<gray>4. Upgrade your island with <white>/is upgrades</white> (needs island value + Roses + Dollars).");
        Text.raw(p, "<gray>5. Your next goal is always on the scoreboard. <white>/path</white>, <white>/missions</white>, <white>/top</white>.");
    }

    public boolean consumeVoucher(Player p, int tier) {
        ItemStack[] c = p.getInventory().getStorageContents();
        for (ItemStack s : c) {
            if (SpecialItems.is(s, SpecialItems.MINION_VOUCHER) && SpecialItems.tier(s) == tier
                    && p.getUniqueId().equals(SpecialItems.soulboundTo(s))) {
                s.setAmount(s.getAmount() - 1);
                return true;
            }
        }
        Text.msg(p, "<red>Voucher not found.");
        return false;
    }

    // ------------------------------------------------------------------ claim box
    public void openBox(Player p) {
        Profile prof = plugin.data.profile(p.getUniqueId());
        Menu m = new Menu(6, "<dark_gray>Reward Box <gray>(" + prof.rewardBox.size() + ")");
        for (int i = 0; i < Math.min(45, prof.rewardBox.size()); i++) {
            final int idx = i;
            ItemStack it = ItemSerial.decode(prof.rewardBox.get(i));
            m.set(i, it, e -> {
                if (idx >= prof.rewardBox.size()) return;
                ItemStack item = ItemSerial.decode(prof.rewardBox.get(idx));
                if (!ShopMenus.fits(p, item)) {
                    Text.msg(p, "<red>Not enough inventory space.");
                    return;
                }
                prof.rewardBox.remove(idx);
                plugin.data.dirty(prof);
                p.getInventory().addItem(item);
                openBox(p);
            });
        }
        m.set(49, Menu.icon(Material.HOPPER, "<green>Claim all", List.of("Takes as many items as fit.")), e -> {
            var it = prof.rewardBox.iterator();
            while (it.hasNext()) {
                ItemStack item = ItemSerial.decode(it.next());
                if (!ShopMenus.fits(p, item)) break;
                p.getInventory().addItem(item);
                it.remove();
            }
            plugin.data.dirty(prof);
            openBox(p);
        });
        m.open(p);
    }
}
