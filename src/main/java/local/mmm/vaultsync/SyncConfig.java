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
        String changeTableName,
        String notificationTableName,
        long joinLoadDelayTicks,
        long localScanIntervalTicks,
        long remoteRefreshIntervalMillis,
        long writeSuppressMillisAfterApply,
        double epsilon,
        boolean flushOnQuit,
        int historyRetentionDays,
        int historyMaxRecordsPerPlayerCurrency,
        long historyCleanupIntervalTicks,
        int changesPageSize,
        int changesMaxQueryRecords,
        boolean debugLogging,
        String defaultCurrencyId,
        CurrencyDefinition defaultCurrency,
        NotificationDefaults defaultNotificationDefaults,
        Map<String, CurrencyDefinition> currencies,
        RedisSyncConfig redisSync
) {
}
