package dev.skycore.economy;

import dev.skycore.SkyCore;
import dev.skycore.data.Profile;

import java.util.UUID;

/**
 * The two currencies.
 * Dollars: abundant, transferable, exposed to Vault.
 * Roses: scarce, earned only from skill actions, never transferable. There is deliberately
 * no method that moves Roses between two accounts.
 */
public final class Economy {
    private final SkyCore plugin;

    public Economy(SkyCore plugin) {
        this.plugin = plugin;
    }

    private Profile p(UUID u) {
        return plugin.data.profile(u);
    }

    // ------------------------------------------------------------ Dollars
    public double dollars(UUID u) {
        Profile p = p(u);
        return p == null ? 0 : p.dollars;
    }

    public boolean hasDollars(UUID u, double amount) {
        return dollars(u) + 1e-9 >= amount;
    }

    public void deposit(UUID u, double amount, String source) {
        if (amount <= 0 || Double.isNaN(amount) || Double.isInfinite(amount)) return;
        Profile p = p(u);
        if (p == null) return;
        p.dollars += amount;
        plugin.data.dirty(p);
        plugin.data.econ("dollars", source, amount);
    }

    public boolean withdraw(UUID u, double amount, String source) {
        if (amount < 0 || Double.isNaN(amount)) return false;
        if (amount == 0) return true;
        Profile p = p(u);
        if (p == null || p.dollars + 1e-9 < amount) return false;
        p.dollars = Math.max(0, p.dollars - amount);
        plugin.data.dirty(p);
        plugin.data.econ("dollars", source, -amount);
        return true;
    }

    /** Player-to-player Dollar transfer (pay, auction house, shops). Logged as neutral transfer. */
    public boolean transfer(UUID from, UUID to, double amount, double fee, String source) {
        if (from.equals(to) || amount <= 0) return false;
        if (!withdraw(from, amount, "transfer:" + source)) return false;
        double net = amount - fee;
        Profile t = p(to);
        if (t == null) {
            deposit(from, amount, "refund:" + source);
            return false;
        }
        t.dollars += net;
        plugin.data.dirty(t);
        plugin.data.econ("dollars", "transfer:" + source, net);
        return true;
    }

    public void setDollars(UUID u, double amount, String source) {
        Profile p = p(u);
        if (p == null) return;
        plugin.data.econ("dollars", source, amount - p.dollars);
        p.dollars = Math.max(0, amount);
        plugin.data.dirty(p);
    }

    // ------------------------------------------------------------ Roses
    public long roses(UUID u) {
        Profile p = p(u);
        return p == null ? 0 : p.roses;
    }

    /** Only skill payouts (and admin commands) may call this. */
    public void addRoses(UUID u, long amount, String source) {
        if (amount <= 0) return;
        Profile p = p(u);
        if (p == null) return;
        p.roses += amount;
        plugin.data.dirty(p);
        plugin.data.econ("roses", source, amount);
    }

    public boolean takeRoses(UUID u, long amount, String source) {
        if (amount < 0) return false;
        if (amount == 0) return true;
        Profile p = p(u);
        if (p == null || p.roses < amount) return false;
        p.roses -= amount;
        plugin.data.dirty(p);
        plugin.data.econ("roses", source, -amount);
        return true;
    }

    /** Atomically charges both currencies, or nothing. */
    public boolean charge(UUID u, long roses, double dollars, String source) {
        if (roses(u) < roses || !hasDollars(u, dollars)) return false;
        takeRoses(u, roses, source);
        withdraw(u, dollars, source);
        return true;
    }
}
