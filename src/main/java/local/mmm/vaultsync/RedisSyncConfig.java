package local.mmm.vaultsync;

public record RedisSyncConfig(
        boolean enabled,
        String host,
        int port,
        String password,
        int database,
        String channel,
        long reconnectDelayMillis
) {
}
