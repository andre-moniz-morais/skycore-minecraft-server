package dev.skycore.skills;

import org.bukkit.Material;

public enum Skill {
    MINING("Mining", Material.IRON_PICKAXE),
    FARMING("Farming", Material.GOLDEN_HOE),
    FISHING("Fishing", Material.FISHING_ROD),
    COMBAT("Combat", Material.IRON_SWORD);

    public final String display;
    public final Material icon;

    Skill(String display, Material icon) {
        this.display = display;
        this.icon = icon;
    }

    public static Skill parse(String s) {
        return s == null ? null : Skill.valueOf(s.trim().toUpperCase());
    }
}
