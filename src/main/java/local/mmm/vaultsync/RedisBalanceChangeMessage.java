package local.mmm.vaultsync;

import java.util.Locale;
import java.util.UUID;

public record RedisBalanceChangeMessage(
        UUID playerId,
        String currencyId,
        long revision,
        String sourceServerId
) {
    public String serialize() {
        return playerId + "|" + currencyId + "|" + revision + "|" + sourceServerId;
    }

    public static RedisBalanceChangeMessage parse(String raw) {
        String[] parts = raw == null ? new String[0] : raw.split("\\|", 4);
        if (parts.length != 4) {
            return null;
        }
        try {
            return new RedisBalanceChangeMessage(
                    UUID.fromString(parts[0]),
                    parts[1].toLowerCase(Locale.ROOT),
                    Long.parseLong(parts[2]),
                    parts[3]
            );
        } catch (Exception exception) {
            return null;
        }
    }
}
