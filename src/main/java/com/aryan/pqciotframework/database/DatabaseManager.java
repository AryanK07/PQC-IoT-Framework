package com.aryan.pqciotframework.database;

import com.aryan.pqciotframework.config.AppConfig;
import com.aryan.pqciotframework.config.RunContext;
import com.aryan.pqciotframework.model.AnomalyEvent;
import com.aryan.pqciotframework.model.PerformanceMetric;
import com.aryan.pqciotframework.model.SensorData;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.PreparedStatement;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

public class DatabaseManager {

    private static final HikariDataSource DATA_SOURCE = buildDataSource();

    private static HikariDataSource buildDataSource() {
        String url = AppConfig.get("db.url");
        String username = AppConfig.get("db.username");
        String password = AppConfig.get("db.password");

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(username);
        config.setPassword(password);
        config.setPoolName("PQC-IoT-HikariPool");
        config.setAutoCommit(true);
        config.setMaximumPoolSize(readIntConfig("db.pool.maxSize", 10));
        config.setMinimumIdle(readIntConfig("db.pool.minIdle", 2));
        config.setConnectionTimeout(readLongConfig("db.pool.connectionTimeoutMs", 10_000L));
        config.setIdleTimeout(readLongConfig("db.pool.idleTimeoutMs", 120_000L));
        config.setMaxLifetime(readLongConfig("db.pool.maxLifetimeMs", 300_000L));
        config.setLeakDetectionThreshold(readLongConfig("db.pool.leakDetectionMs", 15_000L));
        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "250");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");

        HikariDataSource dataSource = new HikariDataSource(config);
        Runtime.getRuntime().addShutdownHook(new Thread(dataSource::close));
        return dataSource;
    }

    private static int readIntConfig(String key, int defaultValue) {
        try {
            String value = AppConfig.get(key);
            if (value == null || value.isBlank()) {
                return defaultValue;
            }
            return Integer.parseInt(value.trim());
        } catch (Exception ignored) {
            return defaultValue;
        }
    }

    private static long readLongConfig(String key, long defaultValue) {
        try {
            String value = AppConfig.get(key);
            if (value == null || value.isBlank()) {
                return defaultValue;
            }
            return Long.parseLong(value.trim());
        } catch (Exception ignored) {
            return defaultValue;
        }
    }

    public static Connection getConnection() throws Exception {
        return DATA_SOURCE.getConnection();
    }

    public static String getCurrentRunId() {
        return RunContext.getCurrentRunId();
    }

    public static String resolveLatestRunId() {
        String sql = "SELECT run_id FROM (" +
                "SELECT run_id, timestamp FROM sensor_data WHERE run_id IS NOT NULL UNION ALL " +
                "SELECT run_id, timestamp FROM anomaly_events WHERE run_id IS NOT NULL UNION ALL " +
                "SELECT run_id, timestamp FROM performance_metrics WHERE run_id IS NOT NULL UNION ALL " +
                "SELECT run_id, timestamp FROM scalability_results WHERE run_id IS NOT NULL UNION ALL " +
                "SELECT run_id, timestamp FROM key_rotation_logs WHERE run_id IS NOT NULL UNION ALL " +
                "SELECT run_id, timestamp FROM system_logs WHERE run_id IS NOT NULL" +
                ") as runs ORDER BY timestamp DESC LIMIT 1";
        try (Connection connection = getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            if (resultSet.next()) {
                String runId = resultSet.getString("run_id");
                return (runId == null || runId.isBlank()) ? "legacy" : runId;
            }
        } catch (Exception ignored) {
        }
        return "legacy";
    }

    public static boolean testConnection() {
        try (Connection connection = getConnection()) {
            return connection != null && !connection.isClosed();
        } catch (Exception e) {
            System.out.println("DB connection error: " + e.getMessage());
            return false;
        }
    }

    public static void createTablesIfNotExist() {
        String[] createTableSql = {
                "CREATE TABLE IF NOT EXISTS sensor_data (" +
                        "id INT AUTO_INCREMENT PRIMARY KEY, " +
                        "device_id VARCHAR(50) NOT NULL, " +
                    "run_id VARCHAR(64) DEFAULT 'legacy', " +
                        "sensor_value DOUBLE NOT NULL, " +
                        "timestamp BIGINT NOT NULL, " +
                        "encrypted_data MEDIUMBLOB, " +
                        "signature MEDIUMBLOB, " +
                        "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP);",

                "CREATE TABLE IF NOT EXISTS anomaly_events (" +
                        "id INT AUTO_INCREMENT PRIMARY KEY, " +
                        "device_id VARCHAR(50) NOT NULL, " +
                    "run_id VARCHAR(64) DEFAULT 'legacy', " +
                        "sensor_value DOUBLE NOT NULL, " +
                        "anomaly_score DOUBLE NOT NULL, " +
                        "is_anomaly BOOLEAN NOT NULL, " +
                        "detection_method VARCHAR(50), " +
                        "timestamp BIGINT NOT NULL, " +
                        "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP);",

                "CREATE TABLE IF NOT EXISTS performance_metrics (" +
                        "id INT AUTO_INCREMENT PRIMARY KEY, " +
                        "operation VARCHAR(50) NOT NULL, " +
                    "run_id VARCHAR(64) DEFAULT 'legacy', " +
                        "execution_time_ms LONG NOT NULL, " +
                        "cpu_usage_percent DOUBLE, " +
                        "memory_usage_mb DOUBLE, " +
                        "timestamp BIGINT NOT NULL, " +
                        "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP);",

                "CREATE TABLE IF NOT EXISTS system_logs (" +
                        "id INT AUTO_INCREMENT PRIMARY KEY, " +
                        "log_hash VARCHAR(256) NOT NULL UNIQUE, " +
                        "previous_hash VARCHAR(256), " +
                    "run_id VARCHAR(64) DEFAULT 'legacy', " +
                        "event_type VARCHAR(50) NOT NULL, " +
                        "event_data TEXT NOT NULL, " +
                        "timestamp BIGINT NOT NULL, " +
                        "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP);",

                "CREATE TABLE IF NOT EXISTS key_rotation_logs (" +
                        "id INT AUTO_INCREMENT PRIMARY KEY, " +
                        "device_id VARCHAR(50) NOT NULL, " +
                    "run_id VARCHAR(64) DEFAULT 'legacy', " +
                        "key_version INT NOT NULL, " +
                        "rotation_reason VARCHAR(100) NOT NULL, " +
                        "mode_name VARCHAR(100) NOT NULL, " +
                        "timestamp BIGINT NOT NULL, " +
                        "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP);",

                "CREATE TABLE IF NOT EXISTS scalability_results (" +
                        "id INT AUTO_INCREMENT PRIMARY KEY, " +
                        "mode_name VARCHAR(50) NOT NULL, " +
                    "run_id VARCHAR(64) DEFAULT 'legacy', " +
                        "device_count INT NOT NULL, " +
                        "total_packets INT NOT NULL, " +
                        "processed_packets INT NOT NULL, " +
                        "avg_latency_ms DOUBLE NOT NULL, " +
                        "throughput_pps DOUBLE NOT NULL, " +
                        "cpu_usage_percent DOUBLE, " +
                        "memory_usage_mb DOUBLE, " +
                        "packet_drop_rate DOUBLE, " +
                        "anomaly_count INT, " +
                        "timestamp BIGINT NOT NULL, " +
                        "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP);"
        };

        try (Connection connection = getConnection()) {
            for (String sql : createTableSql) {
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.execute();
                }
            }

            ensureColumnExists(connection, "sensor_data", "run_id", "VARCHAR(64) DEFAULT 'legacy'");
            ensureColumnExists(connection, "anomaly_events", "run_id", "VARCHAR(64) DEFAULT 'legacy'");
            ensureColumnExists(connection, "performance_metrics", "run_id", "VARCHAR(64) DEFAULT 'legacy'");
            ensureColumnExists(connection, "system_logs", "run_id", "VARCHAR(64) DEFAULT 'legacy'");
            ensureColumnExists(connection, "key_rotation_logs", "run_id", "VARCHAR(64) DEFAULT 'legacy'");
            ensureColumnExists(connection, "scalability_results", "run_id", "VARCHAR(64) DEFAULT 'legacy'");

            ensureIndexExists(connection, "sensor_data", "idx_sensor_data_run_id", "run_id");
            ensureIndexExists(connection, "anomaly_events", "idx_anomaly_events_run_id", "run_id");
            ensureIndexExists(connection, "performance_metrics", "idx_performance_metrics_run_id", "run_id");
            ensureIndexExists(connection, "system_logs", "idx_system_logs_run_id", "run_id");
            ensureIndexExists(connection, "key_rotation_logs", "idx_key_rotation_logs_run_id", "run_id");
            ensureIndexExists(connection, "scalability_results", "idx_scalability_results_run_id", "run_id");

            System.out.println("All tables created successfully!");
        } catch (Exception e) {
            System.out.println("Table creation error: " + e.getMessage());
        }
    }

    private static void ensureColumnExists(Connection connection, String tableName, String columnName, String columnDefinition) throws Exception {
        DatabaseMetaData metaData = connection.getMetaData();
        try (ResultSet columns = metaData.getColumns(connection.getCatalog(), null, tableName, columnName)) {
            if (columns.next()) {
                return;
            }
        }

        String sql = "ALTER TABLE " + tableName + " ADD COLUMN " + columnName + " " + columnDefinition;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.execute();
        }
    }

    private static void ensureIndexExists(Connection connection, String tableName, String indexName, String columnName) throws Exception {
        DatabaseMetaData metaData = connection.getMetaData();
        try (ResultSet indexes = metaData.getIndexInfo(connection.getCatalog(), null, tableName, false, false)) {
            while (indexes.next()) {
                String existingIndex = indexes.getString("INDEX_NAME");
                if (indexName.equalsIgnoreCase(existingIndex)) {
                    return;
                }
            }
        }

        String sql = "CREATE INDEX " + indexName + " ON " + tableName + "(" + columnName + ")";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.execute();
        }
    }

    public static void saveSensorData(SensorData data) {
        String sql = "INSERT INTO sensor_data (device_id, run_id, sensor_value, timestamp, encrypted_data, signature) " +
                "VALUES (?, ?, ?, ?, ?, ?)";

        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setString(1, data.getDeviceId());
            statement.setString(2, getCurrentRunId());
            statement.setDouble(3, data.getSensorValue());
            statement.setLong(4, data.getTimestamp());
            statement.setBytes(5, data.getEncryptedData());
            statement.setBytes(6, data.getSignature());

            statement.executeUpdate();
        } catch (Exception e) {
            System.out.println("Save sensor_data error: " + e.getMessage());
        }
    }

    public static void saveSensorDataBatch(List<SensorData> dataBatch) {
        if (dataBatch == null || dataBatch.isEmpty()) {
            return;
        }

        String sql = "INSERT INTO sensor_data (device_id, run_id, sensor_value, timestamp, encrypted_data, signature) " +
                "VALUES (?, ?, ?, ?, ?, ?)";

        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);

            for (SensorData data : dataBatch) {
                statement.setString(1, data.getDeviceId());
                statement.setString(2, getCurrentRunId());
                statement.setDouble(3, data.getSensorValue());
                statement.setLong(4, data.getTimestamp());
                statement.setBytes(5, data.getEncryptedData());
                statement.setBytes(6, data.getSignature());
                statement.addBatch();
            }

            statement.executeBatch();
            connection.commit();
            connection.setAutoCommit(originalAutoCommit);
        } catch (Exception e) {
            System.out.println("Save sensor_data batch error: " + e.getMessage());
        }
    }

    public static void saveAnomalyEvent(AnomalyEvent event) {
        String sql = "INSERT INTO anomaly_events (device_id, run_id, sensor_value, anomaly_score, is_anomaly, detection_method, timestamp) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?)";

        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setString(1, event.getDeviceId());
            statement.setString(2, getCurrentRunId());
            statement.setDouble(3, event.getSensorValue());
            statement.setDouble(4, event.getAnomalyScore());
            statement.setBoolean(5, event.isAnomaly());
            statement.setString(6, event.getDetectionMethod());
            statement.setLong(7, event.getTimestamp());

            statement.executeUpdate();
        } catch (Exception e) {
            System.out.println("Save anomaly_events error: " + e.getMessage());
        }
    }

    public static void saveAnomalyEventsBatch(List<AnomalyEvent> events) {
        if (events == null || events.isEmpty()) {
            return;
        }

        String sql = "INSERT INTO anomaly_events (device_id, run_id, sensor_value, anomaly_score, is_anomaly, detection_method, timestamp) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?)";

        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);

            for (AnomalyEvent event : events) {
                statement.setString(1, event.getDeviceId());
                statement.setString(2, getCurrentRunId());
                statement.setDouble(3, event.getSensorValue());
                statement.setDouble(4, event.getAnomalyScore());
                statement.setBoolean(5, event.isAnomaly());
                statement.setString(6, event.getDetectionMethod());
                statement.setLong(7, event.getTimestamp());
                statement.addBatch();
            }

            statement.executeBatch();
            connection.commit();
            connection.setAutoCommit(originalAutoCommit);
        } catch (Exception e) {
            System.out.println("Save anomaly_events batch error: " + e.getMessage());
        }
    }

    public static void savePerformanceMetric(PerformanceMetric metric) {
        String sql = "INSERT INTO performance_metrics (operation, run_id, execution_time_ms, cpu_usage_percent, memory_usage_mb, timestamp) " +
            "VALUES (?, ?, ?, ?, ?, ?)";

        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setString(1, metric.getOperation());
            statement.setString(2, getCurrentRunId());
            statement.setLong(3, metric.getExecutionTimeMs());
            statement.setDouble(4, metric.getCpuUsagePercent());
            statement.setDouble(5, metric.getMemoryUsageMb());
            statement.setLong(6, metric.getTimestamp());

            statement.executeUpdate();
        } catch (Exception e) {
            System.out.println("Save performance_metrics error: " + e.getMessage());
        }
    }

    public static void saveKeyRotationLog(String deviceId, int keyVersion, String rotationReason, String modeName, long timestamp) {
        String sql = "INSERT INTO key_rotation_logs (device_id, run_id, key_version, rotation_reason, mode_name, timestamp) " +
            "VALUES (?, ?, ?, ?, ?, ?)";

        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setString(1, deviceId);
            statement.setString(2, getCurrentRunId());
            statement.setInt(3, keyVersion);
            statement.setString(4, rotationReason);
            statement.setString(5, modeName);
            statement.setLong(6, timestamp);

            statement.executeUpdate();
        } catch (Exception e) {
            System.out.println("Save key_rotation_logs error: " + e.getMessage());
        }
    }

    public static void saveScalabilityResult(
            String modeName,
            int deviceCount,
            int totalPackets,
            int processedPackets,
            double avgLatencyMs,
            double throughputPps,
            double cpuUsagePercent,
            double memoryUsageMb,
            double packetDropRate,
            int anomalyCount,
            long timestamp
    ) {
        String sql = "INSERT INTO scalability_results (mode_name, device_count, total_packets, processed_packets, " +
            "run_id, avg_latency_ms, throughput_pps, cpu_usage_percent, memory_usage_mb, packet_drop_rate, anomaly_count, timestamp) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setString(1, modeName);
            statement.setInt(2, deviceCount);
            statement.setInt(3, totalPackets);
            statement.setInt(4, processedPackets);
            statement.setString(5, getCurrentRunId());
            statement.setDouble(6, avgLatencyMs);
            statement.setDouble(7, throughputPps);
            statement.setDouble(8, cpuUsagePercent);
            statement.setDouble(9, memoryUsageMb);
            statement.setDouble(10, packetDropRate);
            statement.setInt(11, anomalyCount);
            statement.setLong(12, timestamp);

            statement.executeUpdate();
        } catch (Exception e) {
            System.out.println("Save scalability_results error: " + e.getMessage());
        }
    }
}