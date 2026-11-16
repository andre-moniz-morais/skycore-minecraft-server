package dev.skycore.ops;

import dev.skycore.SkyCore;
import dev.skycore.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/** Staff alerts (anti-abuse). Logged to console and the audit table. */
public final class Alerts {
    private final SkyCore plugin;

    public Alerts(SkyCore plugin) {
        this.plugin = plugin;
    }

    public void staff(String type, String message) {
        plugin.getLogger().warning("[alert:" + type + "] " + message);
        plugin.data.audit("alert:" + type, message);
        for (Player p : Bukkit.getOnlinePlayers())
            if (p.hasPermission("skycore.staff")) Text.raw(p, "<red>[Staff alert]</red> <gray>" + Text.escape(message));
    }
}
