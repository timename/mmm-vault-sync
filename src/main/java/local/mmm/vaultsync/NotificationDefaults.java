package local.mmm.vaultsync;

import java.math.BigDecimal;
import java.math.RoundingMode;

public record NotificationDefaults(
        boolean enabled,
        boolean notifyIncrease,
        boolean notifyDecrease,
        BigDecimal minAmount
) {
    public BigDecimal normalizedMinAmount() {
        return (minAmount == null ? BigDecimal.ZERO : minAmount).setScale(4, RoundingMode.HALF_UP);
    }
}
