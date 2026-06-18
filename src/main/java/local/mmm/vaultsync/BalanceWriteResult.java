package local.mmm.vaultsync;

import java.util.Optional;

public record BalanceWriteResult(
        BalanceRecord record,
        Optional<BalanceChangeRecord> change
) {
}
