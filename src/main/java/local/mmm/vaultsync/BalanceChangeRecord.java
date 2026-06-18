package local.mmm.vaultsync;

import java.math.BigDecimal;
import java.util.UUID;

public record BalanceChangeRecord(
        long id,
        UUID uuid,
        String currencyId,
        long revision,
        BigDecimal previousBalance,
        BigDecimal newBalance,
        BigDecimal delta,
        String reason,
        String sourceServer,
        long createdAtMillis,
        Long notifiedAtMillis,
        String notifiedServer,
        Long readAtMillis
) {
}
