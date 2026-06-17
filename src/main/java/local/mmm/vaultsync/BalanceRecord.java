package local.mmm.vaultsync;

import java.math.BigDecimal;
import java.util.UUID;

public record BalanceRecord(
        UUID uuid,
        BigDecimal balance,
        long revision,
        long updatedAtMillis,
        String updatedBy
) {
}
