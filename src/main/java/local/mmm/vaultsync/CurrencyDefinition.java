package local.mmm.vaultsync;

import java.math.BigDecimal;
import java.math.RoundingMode;

public record CurrencyDefinition(
        String id,
        String displayName,
        String symbol,
        BigDecimal startingBalance,
        boolean notifyOnChange,
        boolean realtimeSync
) {
    public BigDecimal normalize(BigDecimal amount) {
        return amount.setScale(4, RoundingMode.HALF_UP);
    }

    public BigDecimal normalizedStartingBalance() {
        return normalize(startingBalance);
    }

    public String displayLabel() {
        if (symbol == null || symbol.isBlank()) {
            return displayName;
        }
        return symbol + displayName;
    }
}
