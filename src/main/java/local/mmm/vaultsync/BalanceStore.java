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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class BalanceStore implements AutoCloseable {
    private final HikariDataSource dataSource;
    private final String tableName;
    private final String changeTableName;
    private final String defaultCurrencyId;

    public BalanceStore(SyncConfig config) {
        this.tableName = config.tableName();
        this.changeTableName = config.changeTableName();
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
            }

            if (!hasColumn(connection, "currency_id")) {
                migrateLegacySingleCurrencyTable(connection);
            }
            createChangeTable(connection);
            ensureChangeTableIndexes(connection);
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

    public BalanceWriteResult writeSnapshot(
            UUID uuid,
            String currencyId,
            BigDecimal balance,
            long knownRevision,
            String serverId,
            String reason,
            boolean recordHistory,
            BigDecimal historyPreviousBalance
    )
            throws SQLException {
        String selectSql = "SELECT balance, revision FROM `" + tableName + "` WHERE uuid = ? AND currency_id = ? FOR UPDATE";
        String insertSql = "INSERT INTO `" + tableName + "` "
                + "(uuid, currency_id, balance, revision, updated_at, updated_by) VALUES (?, ?, ?, ?, ?, ?)";
        String updateSql = "UPDATE `" + tableName + "` "
                + "SET balance = ?, revision = ?, updated_at = ?, updated_by = ? WHERE uuid = ? AND currency_id = ?";

        try (Connection connection = dataSource.getConnection()) {
            long currentRevision = 0L;
            BigDecimal previousBalance = null;
            try (PreparedStatement select = connection.prepareStatement(selectSql)) {
                select.setString(1, uuid.toString());
                select.setString(2, currencyId);
                try (ResultSet resultSet = select.executeQuery()) {
                    if (resultSet.next()) {
                        previousBalance = resultSet.getBigDecimal("balance");
                        currentRevision = resultSet.getLong("revision");
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
            BalanceChangeRecord change = null;
            BigDecimal historyPrevious = previousBalance == null ? historyPreviousBalance : previousBalance;
            if (recordHistory && historyPrevious != null && historyPrevious.compareTo(balance) != 0) {
                change = insertChange(connection, uuid, currencyId, nextRevision, historyPrevious, balance, reason, serverId, now);
            }
            connection.commit();
            return new BalanceWriteResult(
                    new BalanceRecord(uuid, currencyId, balance, nextRevision, now, serverId),
                    Optional.ofNullable(change)
            );
        }
    }

    public boolean claimNotice(UUID uuid, String currencyId, long revision, String serverId, long now) throws SQLException {
        String sql = "UPDATE `" + changeTableName + "` "
                + "SET notified_at = ?, notified_server = ?, read_at = ? "
                + "WHERE uuid = ? AND currency_id = ? AND revision = ? AND notified_at IS NULL";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, now);
            statement.setString(2, serverId);
            statement.setLong(3, now);
            statement.setString(4, uuid.toString());
            statement.setString(5, currencyId);
            statement.setLong(6, revision);
            int changed = statement.executeUpdate();
            connection.commit();
            return changed > 0;
        }
    }

    public List<BalanceChangeRecord> loadUnreadChanges(UUID uuid, int limit) throws SQLException {
        String sql = "SELECT id, uuid, currency_id, revision, previous_balance, new_balance, delta, reason, "
                + "source_server, created_at, notified_at, notified_server, read_at "
                + "FROM `" + changeTableName + "` "
                + "WHERE uuid = ? AND read_at IS NULL "
                + "ORDER BY created_at DESC, id DESC LIMIT ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, uuid.toString());
            statement.setInt(2, Math.max(1, limit));
            List<BalanceChangeRecord> changes = new java.util.ArrayList<>();
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    changes.add(readChangeRecord(resultSet));
                }
            }
            connection.commit();
            return changes;
        }
    }

    public List<BalanceChangeRecord> loadRecentChanges(UUID uuid, int limit) throws SQLException {
        String sql = "SELECT id, uuid, currency_id, revision, previous_balance, new_balance, delta, reason, "
                + "source_server, created_at, notified_at, notified_server, read_at "
                + "FROM `" + changeTableName + "` "
                + "WHERE uuid = ? "
                + "ORDER BY created_at DESC, id DESC LIMIT ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, uuid.toString());
            statement.setInt(2, Math.max(1, limit));
            List<BalanceChangeRecord> changes = new java.util.ArrayList<>();
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    changes.add(readChangeRecord(resultSet));
                }
            }
            connection.commit();
            return changes;
        }
    }

    public int markChangesRead(UUID uuid, List<Long> ids, long now) throws SQLException {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        String sql = "UPDATE `" + changeTableName + "` SET read_at = ? WHERE uuid = ? AND id IN (" + placeholders + ")";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, now);
            statement.setString(2, uuid.toString());
            for (int index = 0; index < ids.size(); index++) {
                statement.setLong(index + 3, ids.get(index));
            }
            int changed = statement.executeUpdate();
            connection.commit();
            return changed;
        }
    }

    public int cleanupChangeHistory(long olderThanMillis, int maxRecordsPerPlayerCurrency) throws SQLException {
        int deleted = 0;
        try (Connection connection = dataSource.getConnection()) {
            if (olderThanMillis > 0L) {
                String deleteOldSql = "DELETE FROM `" + changeTableName + "` WHERE created_at < ?";
                try (PreparedStatement statement = connection.prepareStatement(deleteOldSql)) {
                    statement.setLong(1, olderThanMillis);
                    deleted += statement.executeUpdate();
                }
            }

            if (maxRecordsPerPlayerCurrency > 0) {
                String deleteOverflowSql = "DELETE changes FROM `" + changeTableName + "` changes "
                        + "WHERE ("
                        + "SELECT COUNT(*) FROM `" + changeTableName + "` newer "
                        + "WHERE newer.uuid = changes.uuid "
                        + "AND newer.currency_id = changes.currency_id "
                        + "AND (newer.created_at > changes.created_at "
                        + "OR (newer.created_at = changes.created_at AND newer.id > changes.id))"
                        + ") >= ?";
                try (PreparedStatement statement = connection.prepareStatement(deleteOverflowSql)) {
                    statement.setInt(1, maxRecordsPerPlayerCurrency);
                    deleted += statement.executeUpdate();
                }
            }

            connection.commit();
        }
        return deleted;
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

    private void createChangeTable(Connection connection) throws SQLException {
        String sql = "CREATE TABLE IF NOT EXISTS `" + changeTableName + "` ("
                + "`id` BIGINT NOT NULL AUTO_INCREMENT,"
                + "`uuid` CHAR(36) NOT NULL,"
                + "`currency_id` VARCHAR(64) NOT NULL,"
                + "`revision` BIGINT NOT NULL,"
                + "`previous_balance` DECIMAL(19,4) NOT NULL,"
                + "`new_balance` DECIMAL(19,4) NOT NULL,"
                + "`delta` DECIMAL(19,4) NOT NULL,"
                + "`reason` VARCHAR(128) NOT NULL,"
                + "`source_server` VARCHAR(64) NOT NULL,"
                + "`created_at` BIGINT NOT NULL,"
                + "`notified_at` BIGINT NULL,"
                + "`notified_server` VARCHAR(64) NULL,"
                + "`read_at` BIGINT NULL,"
                + "PRIMARY KEY (`id`),"
                + "UNIQUE KEY `uk_balance_change_revision` (`uuid`, `currency_id`, `revision`),"
                + "KEY `idx_balance_change_unread` (`uuid`, `read_at`, `created_at`),"
                + "KEY `idx_balance_change_recent` (`uuid`, `created_at`),"
                + "KEY `idx_balance_change_cleanup` (`uuid`, `currency_id`, `created_at`, `id`)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci";
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private void ensureChangeTableIndexes(Connection connection) throws SQLException {
        if (!hasIndex(connection, changeTableName, "idx_balance_change_cleanup")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("ALTER TABLE `" + changeTableName + "` "
                        + "ADD INDEX `idx_balance_change_cleanup` (`uuid`, `currency_id`, `created_at`, `id`)");
            }
        }
    }

    private BalanceChangeRecord insertChange(
            Connection connection,
            UUID uuid,
            String currencyId,
            long revision,
            BigDecimal previousBalance,
            BigDecimal newBalance,
            String reason,
            String sourceServer,
            long now
    ) throws SQLException {
        String sql = "INSERT INTO `" + changeTableName + "` "
                + "(uuid, currency_id, revision, previous_balance, new_balance, delta, reason, source_server, created_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
        BigDecimal delta = newBalance.subtract(previousBalance);
        try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, uuid.toString());
            statement.setString(2, currencyId);
            statement.setLong(3, revision);
            statement.setBigDecimal(4, previousBalance);
            statement.setBigDecimal(5, newBalance);
            statement.setBigDecimal(6, delta);
            statement.setString(7, reason == null || reason.isBlank() ? "unknown" : reason);
            statement.setString(8, sourceServer);
            statement.setLong(9, now);
            statement.executeUpdate();
            try (ResultSet generatedKeys = statement.getGeneratedKeys()) {
                long id = generatedKeys.next() ? generatedKeys.getLong(1) : 0L;
                return new BalanceChangeRecord(id, uuid, currencyId, revision, previousBalance, newBalance, delta,
                        reason, sourceServer, now, null, null, null);
            }
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

    private boolean hasIndex(Connection connection, String targetTableName, String indexName) throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        try (ResultSet resultSet = metaData.getIndexInfo(connection.getCatalog(), null, targetTableName, false, false)) {
            while (resultSet.next()) {
                if (indexName.equalsIgnoreCase(resultSet.getString("INDEX_NAME"))) {
                    return true;
                }
            }
        }
        return false;
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

    private BalanceChangeRecord readChangeRecord(ResultSet resultSet) throws SQLException {
        long notifiedAt = resultSet.getLong("notified_at");
        Long nullableNotifiedAt = resultSet.wasNull() ? null : notifiedAt;
        long readAt = resultSet.getLong("read_at");
        Long nullableReadAt = resultSet.wasNull() ? null : readAt;
        return new BalanceChangeRecord(
                resultSet.getLong("id"),
                UUID.fromString(resultSet.getString("uuid")),
                resultSet.getString("currency_id"),
                resultSet.getLong("revision"),
                resultSet.getBigDecimal("previous_balance"),
                resultSet.getBigDecimal("new_balance"),
                resultSet.getBigDecimal("delta"),
                resultSet.getString("reason"),
                resultSet.getString("source_server"),
                resultSet.getLong("created_at"),
                nullableNotifiedAt,
                resultSet.getString("notified_server"),
                nullableReadAt
        );
    }

    @Override
    public void close() {
        dataSource.close();
    }
}
