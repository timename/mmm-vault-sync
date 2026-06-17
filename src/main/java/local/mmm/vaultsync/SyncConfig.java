package local.mmm.vaultsync;

public record SyncConfig(
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
        boolean debugLogging
) {
}
