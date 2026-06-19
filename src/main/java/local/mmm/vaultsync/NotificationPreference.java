package local.mmm.vaultsync;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

public record NotificationPreference(
        UUID uuid,
        String currencyId,
        boolean enabled,
        boolean notifyIncrease,
        boolean notifyDecrease,
        BigDecimal minAmount
) {
    public BigDecimal normalizedMinAmount() {
        return (minAmount == null ? BigDecimal.ZERO : minAmount).setScale(4, RoundingMode.HALF_UP);
    }
}

