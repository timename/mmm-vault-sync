package local.mmm.vaultsync;

import java.util.Map;

public record SyncConfig(
        String language,
        String serverId,
        String jdbcUrl,
        String username,
        String password,
        int poolSize,
        long connectTimeoutMillis,
        String tableName,
        long joinLoadDelayTicks,
        long localScanIntervalTicks,
        long remoteRefreshIntervalMillis,
        long writeSuppressMillisAfterApply,
        double epsilon,
        boolean flushOnQuit,
        boolean debugLogging,
        String defaultCurrencyId,
        CurrencyDefinition defaultCurrency,
        Map<String, CurrencyDefinition> currencies,
        RedisSyncConfig redisSync
) {
}
