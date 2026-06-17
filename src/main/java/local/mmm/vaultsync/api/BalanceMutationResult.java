package local.mmm.vaultsync.api;

import java.math.BigDecimal;
import java.util.UUID;

public record BalanceMutationResult(
        boolean success,
        String message,
        UUID playerId,
        String currencyId,
        BigDecimal previousBalance,
        BigDecimal newBalance,
        BigDecimal changedAmount,
        long revision
) {
    public static BalanceMutationResult failed(UUID playerId, String currencyId, String message) {
        BigDecimal zero = BigDecimal.ZERO.setScale(4);
        return new BalanceMutationResult(false, message, playerId, currencyId, zero, zero, zero, 0L);
    }
}
