package local.mmm.vaultsync;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class BalanceStore implements AutoCloseable {
    private final HikariDataSource dataSource;
    private final String tableName;
    private final String defaultCurrencyId;

    public BalanceStore(SyncConfig config) {
        this.tableName = config.tableName();
        this.defaultCurrencyId = config.defaultCurrencyId();

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
        try (Connection connection = dataSource.getConnection()) {
            if (!tableExists(connection)) {
                createTable(connection);
                connection.commit();
                return;
            }

            if (!hasColumn(connection, "currency_id")) {
                migrateLegacySingleCurrencyTable(connection);
            }
            connection.commit();
        }
    }

    public Optional<BalanceRecord> load(UUID uuid, String currencyId) throws SQLException {
        String sql = "SELECT uuid, currency_id, balance, revision, updated_at, updated_by FROM `" + tableName + "` "
                + "WHERE uuid = ? AND currency_id = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, uuid.toString());
            statement.setString(2, currencyId);
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

    public Map<String, BalanceRecord> loadAll(UUID uuid) throws SQLException {
        String sql = "SELECT uuid, currency_id, balance, revision, updated_at, updated_by FROM `" + tableName + "` "
                + "WHERE uuid = ?";
        Map<String, BalanceRecord> records = new LinkedHashMap<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, uuid.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    BalanceRecord record = readRecord(resultSet);
                    records.put(record.currencyId(), record);
                }
            }
            connection.commit();
        }
        return records;
    }

    public BalanceRecord writeSnapshot(UUID uuid, String currencyId, BigDecimal balance, long knownRevision, String serverId)
            throws SQLException {
        String selectSql = "SELECT revision FROM `" + tableName + "` WHERE uuid = ? AND currency_id = ? FOR UPDATE";
        String insertSql = "INSERT INTO `" + tableName + "` "
                + "(uuid, currency_id, balance, revision, updated_at, updated_by) VALUES (?, ?, ?, ?, ?, ?)";
        String updateSql = "UPDATE `" + tableName + "` "
                + "SET balance = ?, revision = ?, updated_at = ?, updated_by = ? WHERE uuid = ? AND currency_id = ?";

        try (Connection connection = dataSource.getConnection()) {
            long currentRevision = 0L;
            try (PreparedStatement select = connection.prepareStatement(selectSql)) {
                select.setString(1, uuid.toString());
                select.setString(2, currencyId);
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
                    insert.setString(2, currencyId);
                    insert.setBigDecimal(3, balance);
                    insert.setLong(4, nextRevision);
                    insert.setLong(5, now);
                    insert.setString(6, serverId);
                    insert.executeUpdate();
                }
            } else {
                try (PreparedStatement update = connection.prepareStatement(updateSql)) {
                    update.setBigDecimal(1, balance);
                    update.setLong(2, nextRevision);
                    update.setLong(3, now);
                    update.setString(4, serverId);
                    update.setString(5, uuid.toString());
                    update.setString(6, currencyId);
                    update.executeUpdate();
                }
            }
            connection.commit();
            return new BalanceRecord(uuid, currencyId, balance, nextRevision, now, serverId);
        }
    }

    private void createTable(Connection connection) throws SQLException {
        String sql = "CREATE TABLE IF NOT EXISTS `" + tableName + "` ("
                + "`uuid` CHAR(36) NOT NULL,"
                + "`currency_id` VARCHAR(64) NOT NULL,"
                + "`balance` DECIMAL(19,4) NOT NULL,"
                + "`revision` BIGINT NOT NULL,"
                + "`updated_at` BIGINT NOT NULL,"
                + "`updated_by` VARCHAR(64) NOT NULL,"
                + "PRIMARY KEY (`uuid`, `currency_id`)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci";
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private boolean tableExists(Connection connection) throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        try (ResultSet resultSet = metaData.getTables(connection.getCatalog(), null, tableName, new String[]{"TABLE"})) {
            return resultSet.next();
        }
    }

    private boolean hasColumn(Connection connection, String columnName) throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        try (ResultSet resultSet = metaData.getColumns(connection.getCatalog(), null, tableName, columnName)) {
            return resultSet.next();
        }
    }

    private void migrateLegacySingleCurrencyTable(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE `" + tableName + "` "
                    + "ADD COLUMN `currency_id` VARCHAR(64) NOT NULL DEFAULT '" + defaultCurrencyId + "' AFTER `uuid`");
            statement.execute("ALTER TABLE `" + tableName + "` DROP PRIMARY KEY, ADD PRIMARY KEY (`uuid`, `currency_id`)");
        }
    }

    private BalanceRecord readRecord(ResultSet resultSet) throws SQLException {
        return new BalanceRecord(
                UUID.fromString(resultSet.getString("uuid")),
                resultSet.getString("currency_id"),
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
