package dev.skycore.gui;

import dev.skycore.SkyCore;
import dev.skycore.util.Text;
import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Captures the next chat line of a player (e.g. a price). Type "cancel" to abort. */
public final class ChatInput implements Listener {
    private final SkyCore plugin;
    private final Map<UUID, Consumer<String>> pending = new ConcurrentHashMap<>();

    public ChatInput(SkyCore plugin) {
        this.plugin = plugin;
    }

    public void ask(Player p, String prompt, Consumer<String> then) {
        p.closeInventory();
        pending.put(p.getUniqueId(), then);
        Text.msg(p, prompt + " <dark_gray>(type <white>cancel</white> to abort)");
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent e) {
        Consumer<String> c = pending.remove(e.getPlayer().getUniqueId());
        if (c == null) return;
        e.setCancelled(true);
        String msg = Text.plain(e.message()).trim();
        if (msg.equalsIgnoreCase("cancel")) {
            Bukkit.getScheduler().runTask(plugin, () -> Text.msg(e.getPlayer(), "Cancelled."));
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> c.accept(msg));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        pending.remove(e.getPlayer().getUniqueId());
    }
}
