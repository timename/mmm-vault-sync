package local.mmm.vaultsync;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

import java.math.BigDecimal;
import java.util.Locale;

public final class VaultSyncPlaceholderExpansion extends PlaceholderExpansion {
    private final MMMVaultSyncPlugin plugin;

    public VaultSyncPlaceholderExpansion(MMMVaultSyncPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getIdentifier() {
        return "mmmvaultsync";
    }

    @Override
    public String getAuthor() {
        return "OpenAI";
    }

    @Override
    public String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        if (params == null || params.isBlank()) {
            return "";
        }

        String normalized = params.toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "phase" -> plugin.getPhase().name();
            case "maintenance" -> String.valueOf(plugin.isMaintenanceMode());
            case "drain" -> String.valueOf(plugin.isDrainCompleted());
            case "verify" -> String.valueOf(plugin.isVerifyCompleted());
            case "default_currency" -> plugin.getDefaultCurrencyId();
            case "currency_count" -> String.valueOf(plugin.getCurrencies().size());
            default -> resolveDynamic(player, normalized);
        };
    }

    private String resolveDynamic(OfflinePlayer player, String params) {
        if (params.equals("balance")) {
            return balance(player, plugin.getDefaultCurrencyId());
        }
        if (params.startsWith("balance_")) {
            return balance(player, params.substring("balance_".length()));
        }
        if (params.startsWith("currency_name_")) {
            return currencyName(params.substring("currency_name_".length()));
        }
        if (params.startsWith("currency_symbol_")) {
            return currencySymbol(params.substring("currency_symbol_".length()));
        }
        return "";
    }

    private String balance(OfflinePlayer player, String currencyId) {
        if (player == null || player.getUniqueId() == null) {
            return "";
        }
        CurrencyDefinition currency = plugin.getCurrencies().get(currencyId);
        if (currency == null) {
            return "";
        }
        BigDecimal balance = plugin.getBalanceSnapshot(player.getUniqueId(), currencyId);
        return plugin.formatCurrencyAmount(currency, balance);
    }

    private String currencyName(String currencyId) {
        CurrencyDefinition currency = plugin.getCurrencies().get(currencyId);
        return currency == null ? "" : currency.displayName();
    }

    private String currencySymbol(String currencyId) {
        CurrencyDefinition currency = plugin.getCurrencies().get(currencyId);
        return currency == null ? "" : currency.symbol();
    }
}
