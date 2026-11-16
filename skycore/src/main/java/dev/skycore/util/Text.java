package dev.skycore.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** MiniMessage helpers and number formatting. All player-facing text is English. */
public final class Text {
    private static final MiniMessage MM = MiniMessage.miniMessage();
    public static String PREFIX = "<dark_gray>[<gradient:#ff5e8a:#ffb86c>SkyCore</gradient><dark_gray>]</dark_gray> <gray>";
    private static final DecimalFormat INT = new DecimalFormat("#,##0", DecimalFormatSymbols.getInstance(Locale.US));
    private static final DecimalFormat DEC = new DecimalFormat("#,##0.##", DecimalFormatSymbols.getInstance(Locale.US));

    private Text() {}

    public static Component mm(String s) {
        return MM.deserialize(s);
    }

    /** Component for item names / lore (no default italics). */
    public static Component item(String s) {
        return MM.deserialize("<!italic>" + s);
    }

    public static List<Component> lore(List<String> lines) {
        List<Component> out = new ArrayList<>(lines.size());
        for (String l : lines) out.add(item("<gray>" + l));
        return out;
    }

    public static String escape(String s) {
        return MM.escapeTags(s == null ? "" : s);
    }

    public static String plain(Component c) {
        return c == null ? "" : PlainTextComponentSerializer.plainText().serialize(c);
    }

    public static void msg(CommandSender to, String s) {
        if (to != null) to.sendMessage(mm(PREFIX + s));
    }

    public static void raw(CommandSender to, String s) {
        if (to != null) to.sendMessage(mm(s));
    }

    public static void bar(Player p, String s) {
        p.sendActionBar(mm(s));
    }

    public static String num(double v) {
        return Math.abs(v - Math.rint(v)) < 1e-9 ? INT.format(v) : DEC.format(v);
    }

    public static String money(double v) {
        return "$" + num(Math.floor(v * 100) / 100.0);
    }

    public static String compact(double v) {
        double a = Math.abs(v);
        if (a >= 1_000_000_000) return DEC.format(v / 1_000_000_000d) + "B";
        if (a >= 1_000_000) return DEC.format(v / 1_000_000d) + "M";
        if (a >= 10_000) return DEC.format(v / 1_000d) + "k";
        return num(Math.floor(v));
    }

    public static String roses(long r) {
        return "<#ff5e8a>" + INT.format(r) + " ✿</#ff5e8a>";
    }

    public static String dollars(double d) {
        return "<green>" + money(d) + "</green>";
    }

    public static String progressBar(double fraction, int length) {
        fraction = Math.max(0, Math.min(1, fraction));
        int full = (int) Math.round(fraction * length);
        return "<green>" + "|".repeat(full) + "<dark_gray>" + "|".repeat(length - full) + "</dark_gray></green>";
    }

    public static String pretty(String enumName) {
        String[] parts = enumName.toLowerCase(Locale.ROOT).split("_");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
        }
        return sb.toString();
    }

    public static String roman(int n) {
        String[] r = {"", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};
        return n >= 0 && n < r.length ? r[n] : String.valueOf(n);
    }

    public static String duration(long seconds) {
        if (seconds < 60) return seconds + "s";
        if (seconds < 3600) return (seconds / 60) + "m " + (seconds % 60) + "s";
        if (seconds < 86400) return (seconds / 3600) + "h " + ((seconds % 3600) / 60) + "m";
        return (seconds / 86400) + "d " + ((seconds % 86400) / 3600) + "h";
    }

    public static Component noItalic(Component c) {
        return c.decoration(TextDecoration.ITALIC, false);
    }
}
