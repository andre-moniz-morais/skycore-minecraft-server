package dev.skycore.util;

import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/** Base64 (de)serialisation of item stacks for the document store. */
public final class ItemSerial {
    private ItemSerial() {}

    public static String encode(ItemStack item) {
        return Base64.getEncoder().encodeToString(item.serializeAsBytes());
    }

    public static ItemStack decode(String s) {
        return ItemStack.deserializeBytes(Base64.getDecoder().decode(s));
    }

    public static String encodeAll(List<ItemStack> items) {
        List<ItemStack> clean = new ArrayList<>();
        for (ItemStack i : items) if (i != null && !i.isEmpty()) clean.add(i);
        return Base64.getEncoder().encodeToString(ItemStack.serializeItemsAsBytes(clean));
    }

    public static List<ItemStack> decodeAll(String s) {
        List<ItemStack> out = new ArrayList<>();
        if (s == null || s.isEmpty()) return out;
        for (ItemStack i : ItemStack.deserializeItemsFromBytes(Base64.getDecoder().decode(s))) {
            if (i != null && !i.isEmpty()) out.add(i);
        }
        return out;
    }
}
