package local.mmm.vaultsync;

import net.milkbowl.vault.economy.AbstractEconomy;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import java.util.List;

public final class SyncEconomyProxy extends AbstractEconomy {
    private final MMMVaultSyncPlugin plugin;
    private final Economy delegate;

    public SyncEconomyProxy(MMMVaultSyncPlugin plugin, Economy delegate) {
        this.plugin = plugin;
        this.delegate = delegate;
    }

    public Economy delegate() {
        return delegate;
    }

    @Override
    public boolean isEnabled() {
        return delegate.isEnabled();
    }

    @Override
    public String getName() {
        return "MMMVaultSyncProxy(" + delegate.getName() + ")";
    }

    @Override
    public boolean hasBankSupport() {
        return delegate.hasBankSupport();
    }

    @Override
    public int fractionalDigits() {
        return delegate.fractionalDigits();
    }

    @Override
    public String format(double amount) {
        return delegate.format(amount);
    }

    @Override
    public String currencyNamePlural() {
        return delegate.currencyNamePlural();
    }

    @Override
    public String currencyNameSingular() {
        return delegate.currencyNameSingular();
    }

    @Override
    public boolean hasAccount(String playerName) {
        return delegate.hasAccount(playerName);
    }

    @Override
    public boolean hasAccount(OfflinePlayer player) {
        return delegate.hasAccount(player);
    }

    @Override
    public boolean hasAccount(String playerName, String worldName) {
        return delegate.hasAccount(playerName, worldName);
    }

    @Override
    public boolean hasAccount(OfflinePlayer player, String worldName) {
        return delegate.hasAccount(player, worldName);
    }

    @Override
    public double getBalance(String playerName) {
        return delegate.getBalance(playerName);
    }

    @Override
    public double getBalance(OfflinePlayer player) {
        return delegate.getBalance(player);
    }

    @Override
    public double getBalance(String playerName, String world) {
        return delegate.getBalance(playerName, world);
    }

    @Override
    public double getBalance(OfflinePlayer player, String world) {
        return delegate.getBalance(player, world);
    }

    @Override
    public boolean has(String playerName, double amount) {
        return delegate.has(playerName, amount);
    }

    @Override
    public boolean has(OfflinePlayer player, double amount) {
        return delegate.has(player, amount);
    }

    @Override
    public boolean has(String playerName, String worldName, double amount) {
        return delegate.has(playerName, worldName, amount);
    }

    @Override
    public boolean has(OfflinePlayer player, String worldName, double amount) {
        return delegate.has(player, worldName, amount);
    }

    @Override
    public EconomyResponse withdrawPlayer(String playerName, double amount) {
        if (!plugin.canAcceptEconomicOperations()) {
            return blockedResponse(amount, "维护模式中，默认货币操作已被锁定");
        }
        EconomyResponse response = delegate.withdrawPlayer(playerName, amount);
        plugin.afterEconomyMutation(resolveOfflinePlayer(playerName), response, "vault-withdraw-name");
        return response;
    }

    @Override
    public EconomyResponse withdrawPlayer(OfflinePlayer player, double amount) {
        if (!plugin.canAcceptEconomicOperations()) {
            return blockedResponse(amount, "维护模式中，默认货币操作已被锁定");
        }
        EconomyResponse response = delegate.withdrawPlayer(player, amount);
        plugin.afterEconomyMutation(player, response, "vault-withdraw-offline");
        return response;
    }

    @Override
    public EconomyResponse withdrawPlayer(String playerName, String worldName, double amount) {
        if (!plugin.canAcceptEconomicOperations()) {
            return blockedResponse(amount, "维护模式中，默认货币操作已被锁定");
        }
        EconomyResponse response = delegate.withdrawPlayer(playerName, worldName, amount);
        plugin.afterEconomyMutation(resolveOfflinePlayer(playerName), response, "vault-withdraw-world-name");
        return response;
    }

    @Override
    public EconomyResponse withdrawPlayer(OfflinePlayer player, String worldName, double amount) {
        if (!plugin.canAcceptEconomicOperations()) {
            return blockedResponse(amount, "维护模式中，默认货币操作已被锁定");
        }
        EconomyResponse response = delegate.withdrawPlayer(player, worldName, amount);
        plugin.afterEconomyMutation(player, response, "vault-withdraw-world-offline");
        return response;
    }

    @Override
    public EconomyResponse depositPlayer(String playerName, double amount) {
        if (!plugin.canAcceptEconomicOperations()) {
            return blockedResponse(amount, "维护模式中，默认货币操作已被锁定");
        }
        EconomyResponse response = delegate.depositPlayer(playerName, amount);
        plugin.afterEconomyMutation(resolveOfflinePlayer(playerName), response, "vault-deposit-name");
        return response;
    }

    @Override
    public EconomyResponse depositPlayer(OfflinePlayer player, double amount) {
        if (!plugin.canAcceptEconomicOperations()) {
            return blockedResponse(amount, "维护模式中，默认货币操作已被锁定");
        }
        EconomyResponse response = delegate.depositPlayer(player, amount);
        plugin.afterEconomyMutation(player, response, "vault-deposit-offline");
        return response;
    }

    @Override
    public EconomyResponse depositPlayer(String playerName, String worldName, double amount) {
        if (!plugin.canAcceptEconomicOperations()) {
            return blockedResponse(amount, "维护模式中，默认货币操作已被锁定");
        }
        EconomyResponse response = delegate.depositPlayer(playerName, worldName, amount);
        plugin.afterEconomyMutation(resolveOfflinePlayer(playerName), response, "vault-deposit-world-name");
        return response;
    }

    @Override
    public EconomyResponse depositPlayer(OfflinePlayer player, String worldName, double amount) {
        if (!plugin.canAcceptEconomicOperations()) {
            return blockedResponse(amount, "维护模式中，默认货币操作已被锁定");
        }
        EconomyResponse response = delegate.depositPlayer(player, worldName, amount);
        plugin.afterEconomyMutation(player, response, "vault-deposit-world-offline");
        return response;
    }

    @Override
    public EconomyResponse createBank(String name, String player) {
        return delegate.createBank(name, player);
    }

    @Override
    public EconomyResponse createBank(String name, OfflinePlayer player) {
        return delegate.createBank(name, player);
    }

    @Override
    public EconomyResponse deleteBank(String name) {
        return delegate.deleteBank(name);
    }

    @Override
    public EconomyResponse bankBalance(String name) {
        return delegate.bankBalance(name);
    }

    @Override
    public EconomyResponse bankHas(String name, double amount) {
        return delegate.bankHas(name, amount);
    }

    @Override
    public EconomyResponse bankWithdraw(String name, double amount) {
        return delegate.bankWithdraw(name, amount);
    }

    @Override
    public EconomyResponse bankDeposit(String name, double amount) {
        return delegate.bankDeposit(name, amount);
    }

    @Override
    public EconomyResponse isBankOwner(String name, String playerName) {
        return delegate.isBankOwner(name, playerName);
    }

    @Override
    public EconomyResponse isBankOwner(String name, OfflinePlayer player) {
        return delegate.isBankOwner(name, player);
    }

    @Override
    public EconomyResponse isBankMember(String name, String playerName) {
        return delegate.isBankMember(name, playerName);
    }

    @Override
    public EconomyResponse isBankMember(String name, OfflinePlayer player) {
        return delegate.isBankMember(name, player);
    }

    @Override
    public List<String> getBanks() {
        return delegate.getBanks();
    }

    @Override
    public boolean createPlayerAccount(String playerName) {
        return delegate.createPlayerAccount(playerName);
    }

    @Override
    public boolean createPlayerAccount(OfflinePlayer player) {
        return delegate.createPlayerAccount(player);
    }

    @Override
    public boolean createPlayerAccount(String playerName, String worldName) {
        return delegate.createPlayerAccount(playerName, worldName);
    }

    @Override
    public boolean createPlayerAccount(OfflinePlayer player, String worldName) {
        return delegate.createPlayerAccount(player, worldName);
    }

    private OfflinePlayer resolveOfflinePlayer(String playerName) {
        if (playerName == null || playerName.isBlank()) {
            return null;
        }
        var online = Bukkit.getPlayerExact(playerName);
        if (online != null) {
            return online;
        }
        return Bukkit.getOfflinePlayer(playerName);
    }

    private EconomyResponse blockedResponse(double amount, String message) {
        return new EconomyResponse(0.0D, amount, EconomyResponse.ResponseType.FAILURE, message);
    }
}
