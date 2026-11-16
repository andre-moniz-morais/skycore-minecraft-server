package dev.skycore.items;

import dev.skycore.SkyCore;
import dev.skycore.config.Settings;
import dev.skycore.util.Keys;
import dev.skycore.util.Text;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Factory and inspector for every SkyCore item.
 * Items bought with Roses (or rewarded) are soulbound: bound to one account, never tradable.
 */
public final class SpecialItems {
    public static final String MINION = "minion";
    public static final String MINION_VOUCHER = "minion_voucher";
    public static final String SELL_CHEST = "sell_chest";
    public static final String SHOP_CHEST = "shop_chest";
    public static final String CHUNK_HOPPER = "chunk_hopper";
    public static final String SEED = "seed";
    public static final String WAND = "wand";
    public static final String WAND_USES = "wand_uses";
    public static final String PET_EGG = "pet_egg";
    public static final String SPAWNER = "spawner";

    private final SkyCore plugin;

    public SpecialItems(SkyCore plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------ inspection
    public static String type(ItemStack i) {
        if (i == null || i.isEmpty() || !i.hasItemMeta()) return null;
        return i.getItemMeta().getPersistentDataContainer().get(Keys.SPECIAL, PersistentDataType.STRING);
    }

    public static boolean is(ItemStack i, String type) {
        return type.equals(type(i));
    }

    public static String data(ItemStack i) {
        if (i == null || !i.hasItemMeta()) return null;
        return i.getItemMeta().getPersistentDataContainer().get(Keys.DATA, PersistentDataType.STRING);
    }

    public static int tier(ItemStack i) {
        if (i == null || !i.hasItemMeta()) return 0;
        Integer t = i.getItemMeta().getPersistentDataContainer().get(Keys.TIER, PersistentDataType.INTEGER);
        return t == null ? 0 : t;
    }

    public static long amount(ItemStack i) {
        if (i == null || !i.hasItemMeta()) return 0;
        Long t = i.getItemMeta().getPersistentDataContainer().get(Keys.AMOUNT, PersistentDataType.LONG);
        return t == null ? 0 : t;
    }

    public static UUID soulboundTo(ItemStack i) {
        if (i == null || i.isEmpty() || !i.hasItemMeta()) return null;
        String s = i.getItemMeta().getPersistentDataContainer().get(Keys.SOULBOUND, PersistentDataType.STRING);
        return s == null ? null : UUID.fromString(s);
    }

    public static boolean isSoulbound(ItemStack i) {
        return soulboundTo(i) != null;
    }

    /** Anything carrying SkyCore data is never sold to the server by material. */
    public static boolean isCustom(ItemStack i) {
        return type(i) != null || isSoulbound(i);
    }

    // ------------------------------------------------------------------ building
    private ItemStack build(Material m, int count, String name, List<String> lore, String type, UUID soulbound,
                            java.util.function.Consumer<PersistentDataContainer> pdc, boolean glint, boolean unique) {
        ItemStack it = new ItemStack(m, Math.max(1, Math.min(count, m.getMaxStackSize())));
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Text.item(name));
        List<String> l = new ArrayList<>(lore);
        if (soulbound != null) {
            l.add("");
            l.add("<red>Soulbound</red> <dark_gray>- cannot be traded or dropped");
        }
        meta.lore(Text.lore(l));
        PersistentDataContainer c = meta.getPersistentDataContainer();
        c.set(Keys.SPECIAL, PersistentDataType.STRING, type);
        if (soulbound != null) c.set(Keys.SOULBOUND, PersistentDataType.STRING, soulbound.toString());
        if (unique) c.set(Keys.UID, PersistentDataType.STRING, UUID.randomUUID().toString());
        if (pdc != null) pdc.accept(c);
        if (glint) meta.setEnchantmentGlintOverride(true);
        it.setItemMeta(meta);
        return it;
    }

    public ItemStack minion(UUID owner, String resource, int tier) {
        Settings.MinionResource r = plugin.settings.minionResources.get(resource);
        Settings.MinionTier t = plugin.settings.minionTiers.get(tier);
        String name = r == null ? resource : r.name();
        List<String> lore = new ArrayList<>();
        lore.add("<white>" + (r == null ? "" : r.skill().display) + " minion");
        if (t != null) {
            lore.add("Produces 1 item every <white>" + t.cycleSeconds() + "s");
            lore.add("Storage: <white>" + t.storageStacks() + " stacks");
        }
        lore.add("Place it on your island. Delivers to a sell chest");
        lore.add("within " + plugin.settings.config.getInt("automation.link-radius", 5) + " blocks, otherwise stores items.");
        return build(Material.PLAYER_HEAD, 1, "<gold>" + name + " Minion <yellow>" + Text.roman(tier), lore, MINION, owner,
                c -> {
                    c.set(Keys.DATA, PersistentDataType.STRING, resource);
                    c.set(Keys.TIER, PersistentDataType.INTEGER, tier);
                }, false, true);
    }

    public ItemStack minionVoucher(UUID owner, int tier) {
        return build(Material.PAPER, 1, "<gold>Minion Voucher <yellow>" + Text.roman(tier),
                List.of("Right-click to choose a tier " + Text.roman(tier) + " minion", "of any type you have unlocked."),
                MINION_VOUCHER, owner, c -> c.set(Keys.TIER, PersistentDataType.INTEGER, tier), true, true);
    }

    public ItemStack sellChest(int count) {
        return build(Material.CHEST, count, "<green>Sell Chest",
                List.of("Sells everything inside every " + plugin.settings.config.getInt("automation.sell-chest-interval-seconds", 5) + "s",
                        "using your island sell multiplier.", "Tradable."), SELL_CHEST, null, null, true, false);
    }

    public ItemStack shopChest(int count) {
        return build(Material.CHEST, count, "<aqua>Shop Chest",
                List.of("A player shop: buy and/or sell one item", "to other players for Dollars.", "Tradable."),
                SHOP_CHEST, null, null, true, false);
    }

    public ItemStack chunkHopper(UUID owner, int count) {
        return build(Material.HOPPER, count, "<light_purple>Chunk Hopper",
                List.of("Collects dropped items in its chunk", "into 54 internal slots every 2s.",
                        "Feeds an adjacent sell chest.", "Max 1 per chunk."), CHUNK_HOPPER, owner, null, true, false);
    }

    public ItemStack seedPack(UUID owner, String crop, int packs) {
        Settings.CropType c = plugin.settings.crops.get(crop);
        int size = plugin.settings.automation.getInt("special-crops.pack-size", 16) * Math.max(1, packs);
        return build(c.seed(), size, "<yellow>Special " + c.name() + " Seed",
                List.of("A permanent crop. Harvest it by hand when ripe:", "drops go straight to your inventory",
                        "and it regrows by itself. Never replant.",
                        "Drop: <white>" + c.amount() + "x " + Text.pretty(c.drop().name()),
                        "Sneak + break to pick it back up."), SEED, owner,
                p -> p.set(Keys.DATA, PersistentDataType.STRING, crop), true, false);
    }

    public ItemStack seedPackSingle(UUID owner, String crop) {
        ItemStack it = seedPack(owner, crop, 1);
        it.setAmount(1);
        return it;
    }

    public ItemStack wand(UUID owner, int tier, long uses) {
        ItemStack it = build(Material.BLAZE_ROD, 1, "<gold>Sell Wand <yellow>" + Text.roman(tier), List.of(), WAND, owner,
                c -> {
                    c.set(Keys.TIER, PersistentDataType.INTEGER, tier);
                    c.set(Keys.AMOUNT, PersistentDataType.LONG, uses);
                }, true, true);
        refreshWand(it);
        return it;
    }

    public void refreshWand(ItemStack it) {
        Settings.WandTier t = plugin.settings.wandTiers.get(tier(it));
        if (t == null) return;
        ItemMeta meta = it.getItemMeta();
        List<String> l = new ArrayList<>();
        l.add("Right-click a container to sell its contents.");
        l.add("Uses: <white>" + amount(it) + "<gray>/" + t.uses());
        l.add("Cooldown: <white>" + (t.cooldownMs() / 1000.0) + "s");
        l.add("Range: <white>" + (t.radius() > 0 ? "all containers within " + t.radius() + " blocks" : "1 container"));
        l.add("Sell value: <white>" + Math.round(t.factor() * 100) + "% <gray>of a sell chest");
        l.add("Recharge: <white>/wand recharge <uses>");
        l.add("");
        l.add("<red>Soulbound</red> <dark_gray>- cannot be traded or dropped");
        meta.lore(Text.lore(l));
        it.setItemMeta(meta);
    }

    public ItemStack wandUses(UUID owner, long uses) {
        return build(Material.PAPER, 1, "<gold>Wand Charge <yellow>+" + uses,
                List.of("Right-click to add " + uses + " uses", "to a sell wand in your inventory."), WAND_USES, owner,
                c -> c.set(Keys.AMOUNT, PersistentDataType.LONG, uses), true, true);
    }

    public ItemStack petEgg(UUID owner, String rarity) {
        Settings.PetRarity r = plugin.settings.petRarities.get(rarity);
        return build(Material.EGG, 1, r.color() + r.name() + " Pet Egg",
                List.of("Right-click to hatch: choose any pet", "of " + r.name() + " rarity."), PET_EGG, owner,
                c -> c.set(Keys.DATA, PersistentDataType.STRING, rarity), true, true);
    }

    public ItemStack spawner(String type, long units) {
        Settings.SpawnerType t = plugin.settings.spawnerTypes.get(type);
        return build(Material.SPAWNER, 1, "<yellow>" + t.name() + " Spawner <gray>x" + Text.num(units),
                List.of("Units: <white>" + Text.num(units), "Dimension: <white>" + t.dimension().display,
                        "Requires the " + t.name() + " type unlocked (/shop).",
                        "Right-click a placed spawner of the same", "type to stack it. Tradable."),
                SPAWNER, null, c -> {
                    c.set(Keys.DATA, PersistentDataType.STRING, type);
                    c.set(Keys.AMOUNT, PersistentDataType.LONG, units);
                }, false, false);
    }

    public ItemStack endFrame(int count) {
        return new ItemStack(Material.END_PORTAL_FRAME, count);
    }
}
