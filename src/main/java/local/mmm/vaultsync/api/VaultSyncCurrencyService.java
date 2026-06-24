package local.mmm.vaultsync.api;

import local.mmm.vaultsync.CurrencyDefinition;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Public multi-currency API.
 *
 * <p>Mutation futures may complete after MMMVaultSync writes storage and applies local state on
 * the Bukkit main thread. Callers must not block the Bukkit main thread with {@code get()} or
 * {@code join()}; use callbacks and return to the main thread from the callback when Bukkit API
 * work is required. Do not block the Bukkit main thread while waiting for these futures.</p>
 */
public interface VaultSyncCurrencyService extends VaultSyncStateService {
    String getDefaultCurrencyId();

    Map<String, CurrencyDefinition> getCurrencies();

    default boolean isKnownCurrency(String currencyId) {
        return getCurrencies().containsKey(currencyId);
    }

    default Optional<CurrencyDefinition> findCurrency(String currencyId) {
        return Optional.ofNullable(getCurrencies().get(currencyId));
    }

    CompletableFuture<BigDecimal> getBalanceAsync(UUID playerId, String currencyId);

    CompletableFuture<BalanceMutationResult> setBalanceAsync(UUID playerId, String currencyId, BigDecimal amount, String reason);

    CompletableFuture<BalanceMutationResult> addBalanceAsync(UUID playerId, String currencyId, BigDecimal amount, String reason);

    CompletableFuture<BalanceMutationResult> removeBalanceAsync(UUID playerId, String currencyId, BigDecimal amount, String reason);
}
