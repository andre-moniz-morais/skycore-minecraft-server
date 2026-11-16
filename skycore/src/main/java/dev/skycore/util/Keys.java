package dev.skycore.util;

import org.bukkit.NamespacedKey;
import org.bukkit.plugin.Plugin;

/** Persistent data keys used on items, blocks, chunks and entities. */
public final class Keys {
    public static NamespacedKey SOULBOUND;   // item: owner uuid
    public static NamespacedKey SPECIAL;     // item/block: special type id
    public static NamespacedKey DATA;        // item/block: extra data (string)
    public static NamespacedKey AMOUNT;      // item: integer amount (spawner units, uses...)
    public static NamespacedKey TIER;        // item: tier
    public static NamespacedKey PLACED;      // chunk: packed player-placed block positions
    public static NamespacedKey MOB;         // entity: spawner mob marker "islandId;posKey;count"
    public static NamespacedKey UID;         // item: unique id (anti-stack / anti-dupe)
    public static NamespacedKey HOLO;        // entity: ranking hologram marker

    private Keys() {}

    public static void init(Plugin p) {
        SOULBOUND = new NamespacedKey(p, "soulbound");
        SPECIAL = new NamespacedKey(p, "special");
        DATA = new NamespacedKey(p, "data");
        AMOUNT = new NamespacedKey(p, "amount");
        TIER = new NamespacedKey(p, "tier");
        PLACED = new NamespacedKey(p, "placed");
        MOB = new NamespacedKey(p, "mob");
        UID = new NamespacedKey(p, "uid");
        HOLO = new NamespacedKey(p, "holo");
    }
}
