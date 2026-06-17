package local.mmm.vaultsync;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;
import java.util.UUID;

public final class BalanceStore implements AutoCloseable {
    private final HikariDataSource dataSource;
    private final String tableName;

    public BalanceStore(SyncConfig config) {
        this.tableName = config.tableName();

        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(config.jdbcUrl());
        hikariConfig.setUsername(config.username());
        hikariConfig.setPassword(config.password());
        hikariConfig.setMaximumPoolSize(config.poolSize());
        hikariConfig.setMinimumIdle(Math.min(2, config.poolSize()));
        hikariConfig.setConnectionTimeout(config.connectTimeoutMillis());
        hikariConfig.setPoolName("MMMVaultSyncPool");
        hikariConfig.setAutoCommit(false);
        hikariConfig.setDriverClassName("com.mysql.cj.jdbc.Driver");
        hikariConfig.addDataSourceProperty("cachePrepStmts", "true");
        hikariConfig.addDataSourceProperty("prepStmtCacheSize", "250");
        hikariConfig.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        hikariConfig.addDataSourceProperty("useServerPrepStmts", "true");
        hikariConfig.addDataSourceProperty("useSSL", "false");
        hikariConfig.addDataSourceProperty("serverTimezone", "UTC");

        this.dataSource = new HikariDataSource(hikariConfig);
    }

    public void ensureSchema() throws SQLException {
        String sql = "CREATE TABLE IF NOT EXISTS `" + tableName + "` ("
                + "`uuid` CHAR(36) NOT NULL,"
                + "`balance` DECIMAL(19,4) NOT NULL,"
                + "`revision` BIGINT NOT NULL,"
                + "`updated_at` BIGINT NOT NULL,"
                + "`updated_by` VARCHAR(64) NOT NULL,"
                + "PRIMARY KEY (`uuid`)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci";
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
            connection.commit();
        }
    }

    public Optional<BalanceRecord> load(UUID uuid) throws SQLException {
        String sql = "SELECT uuid, balance, revision, updated_at, updated_by FROM `" + tableName + "` WHERE uuid = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, uuid.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    connection.commit();
                    return Optional.empty();
                }
                BalanceRecord record = readRecord(resultSet);
                connection.commit();
                return Optional.of(record);
            }
        }
    }

    public BalanceRecord writeSnapshot(UUID uuid, BigDecimal balance, long knownRevision, String serverId) throws SQLException {
        String selectSql = "SELECT revision FROM `" + tableName + "` WHERE uuid = ? FOR UPDATE";
        String insertSql = "INSERT INTO `" + tableName + "` (uuid, balance, revision, updated_at, updated_by) VALUES (?, ?, ?, ?, ?)";
        String updateSql = "UPDATE `" + tableName + "` SET balance = ?, revision = ?, updated_at = ?, updated_by = ? WHERE uuid = ?";

        try (Connection connection = dataSource.getConnection()) {
            long currentRevision = 0L;
            try (PreparedStatement select = connection.prepareStatement(selectSql)) {
                select.setString(1, uuid.toString());
                try (ResultSet resultSet = select.executeQuery()) {
                    if (resultSet.next()) {
                        currentRevision = resultSet.getLong(1);
                    }
                }
            }

            long nextRevision = Math.max(currentRevision, knownRevision) + 1L;
            long now = System.currentTimeMillis();
            if (currentRevision == 0L) {
                try (PreparedStatement insert = connection.prepareStatement(insertSql)) {
                    insert.setString(1, uuid.toString());
                    insert.setBigDecimal(2, balance);
                    insert.setLong(3, nextRevision);
                    insert.setLong(4, now);
                    insert.setString(5, serverId);
                    insert.executeUpdate();
                }
            } else {
                try (PreparedStatement update = connection.prepareStatement(updateSql)) {
                    update.setBigDecimal(1, balance);
                    update.setLong(2, nextRevision);
                    update.setLong(3, now);
                    update.setString(4, serverId);
                    update.setString(5, uuid.toString());
                    update.executeUpdate();
                }
            }
            connection.commit();
            return new BalanceRecord(uuid, balance, nextRevision, now, serverId);
        }
    }

    private BalanceRecord readRecord(ResultSet resultSet) throws SQLException {
        return new BalanceRecord(
                UUID.fromString(resultSet.getString("uuid")),
                resultSet.getBigDecimal("balance"),
                resultSet.getLong("revision"),
                resultSet.getLong("updated_at"),
                resultSet.getString("updated_by")
        );
    }

    @Override
    public void close() {
        dataSource.close();
    }
}
