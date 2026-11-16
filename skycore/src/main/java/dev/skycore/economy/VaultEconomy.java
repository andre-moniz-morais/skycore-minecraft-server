package dev.skycore.economy;

import dev.skycore.SkyCore;
import dev.skycore.data.Profile;
import dev.skycore.util.Text;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import java.util.List;

/** Exposes Dollars (and only Dollars) through the Vault API. Roses are never exposed. */
@SuppressWarnings("deprecation")
public final class VaultEconomy implements net.milkbowl.vault.economy.Economy {
    private final SkyCore plugin;

    public VaultEconomy(SkyCore plugin) {
        this.plugin = plugin;
    }

    private Profile prof(OfflinePlayer p) {
        return p == null ? null : plugin.data.profile(p.getUniqueId());
    }

    private Profile prof(String name) {
        return plugin.data.byName(name);
    }

    @Override public boolean isEnabled() { return plugin.isEnabled(); }
    @Override public String getName() { return "SkyCore"; }
    @Override public boolean hasBankSupport() { return false; }
    @Override public int fractionalDigits() { return 2; }
    @Override public String format(double v) { return Text.money(v); }
    @Override public String currencyNamePlural() { return "Dollars"; }
    @Override public String currencyNameSingular() { return "Dollar"; }

    @Override public boolean hasAccount(String name) { return prof(name) != null; }
    @Override public boolean hasAccount(OfflinePlayer p) { return prof(p) != null; }
    @Override public boolean hasAccount(String name, String world) { return hasAccount(name); }
    @Override public boolean hasAccount(OfflinePlayer p, String world) { return hasAccount(p); }

    @Override public double getBalance(String name) { Profile p = prof(name); return p == null ? 0 : p.dollars; }
    @Override public double getBalance(OfflinePlayer p) { Profile x = prof(p); return x == null ? 0 : x.dollars; }
    @Override public double getBalance(String name, String world) { return getBalance(name); }
    @Override public double getBalance(OfflinePlayer p, String world) { return getBalance(p); }

    @Override public boolean has(String name, double amount) { return getBalance(name) >= amount; }
    @Override public boolean has(OfflinePlayer p, double amount) { return getBalance(p) >= amount; }
    @Override public boolean has(String name, String world, double amount) { return has(name, amount); }
    @Override public boolean has(OfflinePlayer p, String world, double amount) { return has(p, amount); }

    private EconomyResponse withdraw(Profile p, double amount) {
        if (p == null) return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "No account");
        if (amount < 0) return new EconomyResponse(0, p.dollars, EconomyResponse.ResponseType.FAILURE, "Negative amount");
        if (!plugin.eco.withdraw(p.uuid, amount, "vault"))
            return new EconomyResponse(0, p.dollars, EconomyResponse.ResponseType.FAILURE, "Insufficient funds");
        return new EconomyResponse(amount, p.dollars, EconomyResponse.ResponseType.SUCCESS, null);
    }

    private EconomyResponse deposit(Profile p, double amount) {
        if (p == null) return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "No account");
        if (amount < 0) return new EconomyResponse(0, p.dollars, EconomyResponse.ResponseType.FAILURE, "Negative amount");
        plugin.eco.deposit(p.uuid, amount, "vault");
        return new EconomyResponse(amount, p.dollars, EconomyResponse.ResponseType.SUCCESS, null);
    }

    @Override public EconomyResponse withdrawPlayer(String name, double amount) { return withdraw(prof(name), amount); }
    @Override public EconomyResponse withdrawPlayer(OfflinePlayer p, double amount) { return withdraw(prof(p), amount); }
    @Override public EconomyResponse withdrawPlayer(String name, String world, double amount) { return withdrawPlayer(name, amount); }
    @Override public EconomyResponse withdrawPlayer(OfflinePlayer p, String world, double amount) { return withdrawPlayer(p, amount); }
    @Override public EconomyResponse depositPlayer(String name, double amount) { return deposit(prof(name), amount); }
    @Override public EconomyResponse depositPlayer(OfflinePlayer p, double amount) { return deposit(prof(p), amount); }
    @Override public EconomyResponse depositPlayer(String name, String world, double amount) { return depositPlayer(name, amount); }
    @Override public EconomyResponse depositPlayer(OfflinePlayer p, String world, double amount) { return depositPlayer(p, amount); }

    @Override public boolean createPlayerAccount(String name) { return hasAccount(name); }
    @Override public boolean createPlayerAccount(OfflinePlayer p) {
        if (p.getName() != null) plugin.data.profileOrCreate(p.getUniqueId(), p.getName());
        return true;
    }
    @Override public boolean createPlayerAccount(String name, String world) { return createPlayerAccount(name); }
    @Override public boolean createPlayerAccount(OfflinePlayer p, String world) { return createPlayerAccount(p); }

    private static EconomyResponse noBank() {
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "Banks are not supported");
    }

    @Override public EconomyResponse createBank(String name, String player) { return noBank(); }
    @Override public EconomyResponse createBank(String name, OfflinePlayer player) { return noBank(); }
    @Override public EconomyResponse deleteBank(String name) { return noBank(); }
    @Override public EconomyResponse bankBalance(String name) { return noBank(); }
    @Override public EconomyResponse bankHas(String name, double amount) { return noBank(); }
    @Override public EconomyResponse bankWithdraw(String name, double amount) { return noBank(); }
    @Override public EconomyResponse bankDeposit(String name, double amount) { return noBank(); }
    @Override public EconomyResponse isBankOwner(String name, String playerName) { return noBank(); }
    @Override public EconomyResponse isBankOwner(String name, OfflinePlayer player) { return noBank(); }
    @Override public EconomyResponse isBankMember(String name, String playerName) { return noBank(); }
    @Override public EconomyResponse isBankMember(String name, OfflinePlayer player) { return noBank(); }
    @Override public List<String> getBanks() { return List.of(); }

    public static void register(SkyCore plugin) {
        if (Bukkit.getPluginManager().getPlugin("Vault") == null && Bukkit.getPluginManager().getPlugin("VaultUnlocked") == null) {
            plugin.getLogger().info("Vault not found - Dollars are not exposed to other plugins.");
            return;
        }
        Bukkit.getServicesManager().register(net.milkbowl.vault.economy.Economy.class, new VaultEconomy(plugin), plugin,
                org.bukkit.plugin.ServicePriority.Highest);
        plugin.getLogger().info("Registered Dollars as Vault economy provider.");
    }
}
