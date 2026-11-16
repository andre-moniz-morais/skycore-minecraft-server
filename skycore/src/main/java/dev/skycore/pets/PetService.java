package dev.skycore.pets;

import dev.skycore.SkyCore;
import dev.skycore.config.Settings;
import dev.skycore.data.Profile;
import dev.skycore.gui.Menu;
import dev.skycore.items.SpecialItems;
import dev.skycore.skills.Skill;
import dev.skycore.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Pets: Roses-only, soulbound, account-wide. Efficiency bonuses only - never more Roses.
 * No entity: pets are pure data (keeps the server light).
 */
public final class PetService implements Listener {
    private final SkyCore plugin;

    public PetService(SkyCore plugin) {
        this.plugin = plugin;
    }

    private int maxLevel() {
        return plugin.settings.pets.getInt("max-level", 50);
    }

    public double xpFor(int level) {
        if (level <= 1) return 0;
        return plugin.settings.pets.getDouble("curve.base", 25) * Math.pow(level, plugin.settings.pets.getDouble("curve.exponent", 2.2));
    }

    public int levelOf(double xp) {
        int l = 1;
        while (l < maxLevel() && xp >= xpFor(l + 1)) l++;
        return l;
    }

    private double scale(Profile.PetData p) {
        return (double) p.level / maxLevel();
    }

    private List<Profile.PetData> active(Profile p) {
        List<Profile.PetData> out = new ArrayList<>();
        for (Profile.PetData d : p.pets) if (p.activePets.contains(d.id)) out.add(d);
        return out;
    }

    public int slots(Profile p) {
        return plugin.settings.pets.getInt("active-slots", 1) + (p.petSlot2 ? 1 : 0);
    }

    public double skillXpBonus(Profile p, Skill s) {
        double b = 0;
        for (Profile.PetData d : active(p)) {
            Settings.PetType t = plugin.settings.petTypes.get(d.type);
            Settings.PetRarity r = plugin.settings.petRarities.get(d.rarity);
            if (t != null && r != null && "skill".equals(t.kind()) && t.skill() == s) b += r.skillXp() * scale(d);
        }
        return b;
    }

    public double sellBonus(Profile p, Skill s) {
        double b = 0;
        for (Profile.PetData d : active(p)) {
            Settings.PetType t = plugin.settings.petTypes.get(d.type);
            Settings.PetRarity r = plugin.settings.petRarities.get(d.rarity);
            if (t == null || r == null) continue;
            if ("skill".equals(t.kind()) && s != null && t.skill() == s) b += r.skillSell() * scale(d);
            if ("general".equals(t.kind())) b += r.generalSell() * scale(d);
        }
        return b;
    }

    public double regrowBonus(Profile p) {
        double b = 0;
        for (Profile.PetData d : active(p)) {
            Settings.PetType t = plugin.settings.petTypes.get(d.type);
            Settings.PetRarity r = plugin.settings.petRarities.get(d.rarity);
            if (t != null && r != null && "regrow".equals(t.kind())) b += r.regrow() * scale(d);
        }
        return Math.min(0.9, b);
    }

    public void onSkillXp(Player player, Profile p, double xp) {
        double share = plugin.settings.pets.getDouble("xp-share", 0.5);
        for (Profile.PetData d : active(p)) {
            if (d.level >= maxLevel()) continue;
            d.xp += xp * share;
            int l = levelOf(d.xp);
            if (l > d.level) {
                d.level = l;
                Text.msg(player, "<light_purple>Your " + name(d) + " <light_purple>reached level " + l + "!");
            }
        }
    }

    public String name(Profile.PetData d) {
        Settings.PetType t = plugin.settings.petTypes.get(d.type);
        Settings.PetRarity r = plugin.settings.petRarities.get(d.rarity);
        return (r == null ? "" : r.color() + r.name() + " ") + (t == null ? d.type : t.name());
    }

    public void add(Player player, String type, String rarity) {
        Profile p = plugin.data.profile(player.getUniqueId());
        Profile.PetData d = new Profile.PetData();
        d.id = UUID.randomUUID().toString().substring(0, 8);
        d.type = type;
        d.rarity = rarity;
        p.pets.add(d);
        if (p.activePets.size() < slots(p)) p.activePets.add(d.id);
        plugin.data.dirty(p);
        Text.msg(player, "<green>New pet: " + name(d) + "<green>!" + (p.activePets.contains(d.id) ? " <gray>(active)" : " <gray>(/pets to activate)"));
    }

    // ------------------------------------------------------------------ menus
    public void menu(Player player) {
        Profile p = plugin.data.profile(player.getUniqueId());
        Menu m = new Menu(6, "<dark_gray>Your Pets <gray>(" + p.activePets.size() + "/" + slots(p) + " active)");
        int slot = 0;
        for (Profile.PetData d : p.pets) {
            if (slot >= 45) break;
            Settings.PetType t = plugin.settings.petTypes.get(d.type);
            Settings.PetRarity r = plugin.settings.petRarities.get(d.rarity);
            if (t == null || r == null) continue;
            boolean on = p.activePets.contains(d.id);
            Settings.PetRarity next = plugin.settings.nextRarity(d.rarity);
            List<String> lore = new ArrayList<>();
            lore.add("Level: <white>" + d.level + "<gray>/" + maxLevel() + " " + Text.progressBar(progressOf(d), 15));
            lore.add(bonusText(t, r, d));
            lore.add(on ? "<green>Active" : "<gray>Inactive");
            lore.add("");
            lore.add("<yellow>Click</yellow>: " + (on ? "deactivate" : "activate"));
            if (next != null) lore.add("<yellow>Shift+Click</yellow>: evolve to " + next.color() + next.name() + " <gray>(" + Text.roses(next.evolveCost()) + "<gray>)");
            m.set(slot++, Menu.icon(t.icon(), name(d), lore, on), e -> {
                if (e.getClick().isShiftClick()) evolve(player, d);
                else toggle(player, d);
                menu(player);
            });
        }
        m.set(49, Menu.icon(Material.EMERALD, "<green>Buy pets", List.of("Open the pet shop.")), e -> shop(player));
        m.open(player);
    }

    private double progressOf(Profile.PetData d) {
        if (d.level >= maxLevel()) return 1;
        double a = xpFor(d.level), b = xpFor(d.level + 1);
        return (d.xp - a) / (b - a);
    }

    private String bonusText(Settings.PetType t, Settings.PetRarity r, Profile.PetData d) {
        double s = scale(d) * 100;
        return switch (t.kind()) {
            case "skill" -> "<aqua>+" + Text.num(Math.round(r.skillXp() * s * 10) / 10.0) + "% " + t.skill().display + " XP, +"
                    + Text.num(Math.round(r.skillSell() * s * 10) / 10.0) + "% " + t.skill().display + " sell value";
            case "general" -> "<aqua>+" + Text.num(Math.round(r.generalSell() * s * 10) / 10.0) + "% sell value (all items)";
            case "regrow" -> "<aqua>-" + Text.num(Math.round(r.regrow() * s * 10) / 10.0) + "% special crop regrow time";
            default -> "";
        };
    }

    private void toggle(Player player, Profile.PetData d) {
        Profile p = plugin.data.profile(player.getUniqueId());
        if (p.activePets.remove(d.id)) {
            plugin.data.dirty(p);
            return;
        }
        if (p.activePets.size() >= slots(p)) {
            Text.msg(player, "<red>All pet slots are in use. Deactivate a pet first.");
            return;
        }
        p.activePets.add(d.id);
        plugin.data.dirty(p);
    }

    private void evolve(Player player, Profile.PetData d) {
        Settings.PetRarity next = plugin.settings.nextRarity(d.rarity);
        if (next == null) return;
        if (!plugin.eco.takeRoses(player.getUniqueId(), next.evolveCost(), "pet_evolve")) {
            Text.msg(player, "<red>Evolving costs " + Text.roses(next.evolveCost()) + "<red>.");
            return;
        }
        d.rarity = next.id();
        plugin.data.dirty(plugin.data.profile(player.getUniqueId()));
        Text.msg(player, "<green>Evolved to " + name(d) + "<green>! Level and XP kept.");
    }

    public void shop(Player player) {
        Menu m = new Menu(6, "<dark_gray>Pet Shop <gray>(Roses only)");
        int row = 0;
        for (Settings.PetType t : plugin.settings.petTypes.values()) {
            int col = 1;
            m.set(row * 9, Menu.icon(t.icon(), "<white>" + t.name(), List.of(switch (t.kind()) {
                case "skill" -> t.skill().display + " XP and sell value";
                case "general" -> "Sell value of every item";
                default -> "Faster special crop regrowth";
            })));
            for (Settings.PetRarity r : plugin.settings.petRarities.values()) {
                Profile.PetData preview = new Profile.PetData();
                preview.level = maxLevel();
                m.set(row * 9 + 1 + col, Menu.icon(t.icon(), r.color() + r.name() + " " + t.name(), List.of(
                        "At level " + maxLevel() + ": " + bonusText(t, r, preview),
                        "Price: " + Text.roses(r.roses()), "Soulbound.", "<green>Click to buy")), e -> {
                    if (!plugin.eco.takeRoses(player.getUniqueId(), r.roses(), "pet_buy")) {
                        Text.msg(player, "<red>You need " + Text.roses(r.roses()) + "<red>.");
                        return;
                    }
                    add(player, t.id(), r.id());
                    player.closeInventory();
                });
                col++;
            }
            row++;
        }
        m.open(player);
    }

    private void hatch(Player player, ItemStack egg) {
        String rarity = SpecialItems.data(egg);
        Settings.PetRarity r = plugin.settings.petRarities.get(rarity);
        if (r == null) return;
        Menu m = new Menu(1, "<dark_gray>Hatch a " + r.name() + " pet");
        int slot = 1;
        for (Settings.PetType t : plugin.settings.petTypes.values()) {
            m.set(slot++, Menu.icon(t.icon(), r.color() + r.name() + " " + t.name(), List.of("<green>Click to hatch")), e -> {
                ItemStack hand = player.getInventory().getItemInMainHand();
                if (!SpecialItems.is(hand, SpecialItems.PET_EGG) || !rarity.equals(SpecialItems.data(hand))) return;
                hand.setAmount(hand.getAmount() - 1);
                add(player, t.id(), rarity);
                player.closeInventory();
            });
        }
        m.open(player);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onUse(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack it = e.getItem();
        String type = SpecialItems.type(it);
        if (SpecialItems.PET_EGG.equals(type)) {
            e.setCancelled(true);
            UUID bound = SpecialItems.soulboundTo(it);
            if (bound != null && !bound.equals(e.getPlayer().getUniqueId())) return;
            hatch(e.getPlayer(), it);
        } else if (SpecialItems.MINION_VOUCHER.equals(type)) {
            e.setCancelled(true);
            plugin.shopMenus.minions(e.getPlayer(), SpecialItems.tier(it));
        }
    }
}
