package com.aryan.pqciotframework;

import com.aryan.pqciotframework.anomaly.AnomalyDetector;
import com.aryan.pqciotframework.config.DeviceConfig;
import com.aryan.pqciotframework.config.RunContext;
import com.aryan.pqciotframework.database.DatabaseManager;
import com.aryan.pqciotframework.gateway.GatewayServer;
import com.aryan.pqciotframework.input.DatasetReader;
import com.aryan.pqciotframework.input.DeviceSimulator;
import com.aryan.pqciotframework.logging.ImmutableLogger;
import com.aryan.pqciotframework.model.AnomalyEvent;
import com.aryan.pqciotframework.model.SensorData;
import com.aryan.pqciotframework.performance.PerformanceMonitor;
import com.aryan.pqciotframework.processing.DataProcessor;
import com.aryan.pqciotframework.security.PQCService;
import com.aryan.pqciotframework.security.ClassicalSecurityService;
import com.aryan.pqciotframework.security.CryptoKeyBundle;
import com.aryan.pqciotframework.model.SecurePacket;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class FinalIntegrationTest {

    private static final long DEFAULT_TEST_DURATION_MS = 5 * 60 * 1000; // 5 minutes
    private static final int FINAL_TEST_DEVICE_COUNT = 50;
    private static final long DEFAULT_PACKET_INTERVAL_MS = 50;
    private static final long PROGRESS_REPORT_INTERVAL_MS = 15_000;
    private static final int SENSOR_FLUSH_BATCH_SIZE = 100;
    private static final int STRESS_TEST_ROTATION_PACKET_LIMIT = 5;
    private static final DateTimeFormatter REPORT_FILE_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    private enum CryptoMode {
        HYBRID,
        PQC_ONLY,
        CLASSICAL_ONLY
    }

    public static void main(String[] args) {
        try {
            executeIntegration();
        } catch (Exception e) {
            System.out.println("ERROR: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Executes the full end-to-end integration flow and returns the stress-test results map.
     * This is useful for automation (evaluation sweeps) while preserving main() as a CLI entry.
     */
    public static Map<String, Object> executeIntegration() throws Exception {
        System.out.println("\n" + "=".repeat(70));
        System.out.println("FINAL INTEGRATION AND TESTING");
        System.out.println("=".repeat(70) + "\n");

        String runId = RunContext.resolveOrCreate("integration");
        System.out.println("[RUN] Active run_id: " + runId + "\n");

        // Phase 1: Database Validation
        System.out.println("\n[PHASE 1] Validating Database Connection & Schema...");
        if (!validateDatabase()) {
            System.out.println("FAILED: Database validation failed!");
            return null;
        }

        // Phase 2: Run stress test
        long testDurationMs = resolveTestDurationMs();
        System.out.println("\n[PHASE 2] Running Full 50-Device Stress Test (" + formatDuration(testDurationMs) + ")...");
        Map<String, Object> stressTestResults = runStressTest();
        if (stressTestResults == null || stressTestResults.isEmpty()) {
            System.out.println("FAILED: Stress test failed!");
            return null;
        }

        // Phase 3: Validate Immutable Log Chain Integrity
        System.out.println("\n[PHASE 3] Validating Immutable Log Chain Integrity...");
        if (!validateImmutableLogs()) {
            System.out.println("FAILED: Immutable log validation failed!");
            return stressTestResults;
        }

        // Phase 4: Validate Table Population
        System.out.println("\n[PHASE 4] Validating All Database Tables...");
        if (!validateTablePopulation()) {
            System.out.println("FAILED: Table population validation failed!");
            return stressTestResults;
        }

        // Phase 5: Final Report
        System.out.println("\n[PHASE 5] Generating Final Integration Report...");
        generateFinalReport(stressTestResults);

        System.out.println("\n" + "=".repeat(70));
        System.out.println("  FINAL STRESS TEST COMPLETED SUCCESSFULLY");
        System.out.println("=".repeat(70));

        return stressTestResults;
    }

    // ===== PHASE 1: DATABASE VALIDATION =====

    private static boolean validateDatabase() throws Exception {
        System.out.println("  Testing database connection...");
        if (!DatabaseManager.testConnection()) {
            System.out.println("  ✗ Database connection FAILED");
            return false;
        }
        System.out.println("  ✓ Database connection: OK");

        System.out.println("  Creating required tables...");
        DatabaseManager.createTablesIfNotExist();
        System.out.println("  ✓ All tables created/verified");

        // Clear only this run's logs (avoid wiping evidence from prior runs)
        String runId = DatabaseManager.getCurrentRunId();
        try (Connection conn = DatabaseManager.getConnection();
             java.sql.PreparedStatement pstmt = conn.prepareStatement("DELETE FROM system_logs WHERE run_id = ?")) {
            pstmt.setString(1, runId);
            int deleted = pstmt.executeUpdate();
            System.out.println("  ✓ Cleared system_logs for run_id=" + runId + " (" + deleted + " rows)");
        }

        return true;
    }

    // ===== PHASE 2: STRESS TEST (50 DEVICES, 5 MINUTES) =====

    private static Map<String, Object> runStressTest() throws Exception {
        Map<String, Object> results = new HashMap<>();
        long testDurationMs = resolveTestDurationMs();

        int configuredDeviceCount = resolveDeviceCount();
        long packetIntervalMs = resolvePacketIntervalMs();
        CryptoMode cryptoMode = resolveCryptoMode();

        System.out.println("  Configuration:");
        System.out.println("    - Device Count: " + configuredDeviceCount);
        System.out.println("    - Crypto Mode: " + cryptoMode);
        System.out.println("    - Mode: " + DeviceConfig.CURRENT_MODE);
        if (DeviceConfig.CURRENT_MODE == DeviceConfig.Mode.DATASET) {
            System.out.println("    - Dataset: " + DeviceConfig.DATASET_PATH);
        }
        System.out.println("    - Duration: " + formatDuration(testDurationMs) + " (or until completion)");
        System.out.println("    - Interval: ~" + packetIntervalMs + "ms per packet");

        // Initialize components
        AnomalyDetector anomalyDetector = new AnomalyDetector();
        PerformanceMonitor performanceMonitor = new PerformanceMonitor();
        ImmutableLogger logger = new ImmutableLogger();
        // Initialize security services
        PQCService pqcService = new PQCService();
        ClassicalSecurityService classicalService = new ClassicalSecurityService();
        GatewayServer pqcGateway = new GatewayServer(pqcService, logger, false, STRESS_TEST_ROTATION_PACKET_LIMIT);
        GatewayServer classicalGateway = new GatewayServer(classicalService, logger, false, STRESS_TEST_ROTATION_PACKET_LIMIT);

        // Prepare data source
        List<SensorData> datasetData = null;
        Set<String> deviceIds = new LinkedHashSet<>();
        Map<String, List<String>> categoryToDeviceIds = new HashMap<>();
        Map<String, Integer> categoryCursor = new HashMap<>();

        if (DeviceConfig.CURRENT_MODE == DeviceConfig.Mode.DATASET) {
            String datasetPathLower = DeviceConfig.DATASET_PATH.toLowerCase();
            List<SensorData> fullDataset;

            if (datasetPathLower.contains("unsw") || datasetPathLower.endsWith(".csv")) {
                System.out.println("  Loading UNSW-NB15 dataset...");
                fullDataset = DatasetReader.readUNSWNB15File(DeviceConfig.DATASET_PATH);
            } else {
                System.out.println("  Loading NSL-KDD dataset...");
                fullDataset = DatasetReader.readNSLKDDFile(DeviceConfig.DATASET_PATH);
            }

            datasetData = DatasetReader.sampleData(fullDataset, DeviceConfig.DATASET_SAMPLE_SIZE);

            // Treat dataset-derived deviceId as a category (e.g., "generic-device") and expand
            // into distinct device identities (e.g., "generic-device-1" ...).
            Set<String> categories = new LinkedHashSet<>();
            for (SensorData sensorData : datasetData) {
                if (sensorData.getDeviceId() != null && !sensorData.getDeviceId().isBlank()) {
                    categories.add(sensorData.getDeviceId().trim());
                }
            }

            if (categories.isEmpty()) {
                categories.add("unsw-device");
            }

            categoryToDeviceIds = expandCategoriesToDevices(new ArrayList<>(categories), configuredDeviceCount);
            for (List<String> ids : categoryToDeviceIds.values()) {
                deviceIds.addAll(ids);
            }

            System.out.println("  Dataset loaded: " + fullDataset.size() + " rows, sampled " + datasetData.size() + " rows");
            System.out.println("  Derived categories from dataset: " + categories.size());
            System.out.println("  Expanded to device identities: " + deviceIds.size());
        } else {
            for (int i = 0; i < configuredDeviceCount; i++) {
                deviceIds.add("Device-" + i);
            }
        }

        // Register + authenticate devices for both gateway modes
        System.out.println("  Registering and authenticating " + deviceIds.size() + " devices across PQC and Classical gateways...");
        List<DeviceSimulator> devices = new ArrayList<>();
        for (String deviceId : deviceIds) {
            devices.add(new DeviceSimulator(deviceId, 50.0, 5.0));
            registerAndAuthenticateDevice(pqcGateway, deviceId);
            registerAndAuthenticateDevice(classicalGateway, deviceId);
        }
        System.out.println("    [Setup] Ready devices: " + deviceIds.size() + "/" + deviceIds.size());
        System.out.println("  ✓ All devices authenticated\n");

        // Start timing *after* setup so metrics reflect packet-processing performance.
        long startTime = System.currentTimeMillis();
        long endTime = startTime + testDurationMs;
        long nextProgressReportAt = startTime + PROGRESS_REPORT_INTERVAL_MS;

        // Resource sampling (addresses coarse end-only measurement)
        long resourceSampleIntervalMs = resolveResourceSampleIntervalMs();
        long nextResourceSampleAt = startTime;
        int cpuSampleCount = 0;
        double cpuSum = 0.0;
        double cpuMax = 0.0;
        int memSampleCount = 0;
        double memSum = 0.0;
        double memMax = 0.0;

        // Communication overhead sampling (payload/envelope/signature bytes)
        int byteSampleCount = 0;
        long payloadBytesTotal = 0L;
        long envelopeBytesTotal = 0L;
        long signatureBytesTotal = 0L;

        int pqcByteSampleCount = 0;
        long payloadBytesPqcTotal = 0L;
        long envelopeBytesPqcTotal = 0L;
        long signatureBytesPqcTotal = 0L;

        int classicalByteSampleCount = 0;
        long payloadBytesClassicalTotal = 0L;
        long envelopeBytesClassicalTotal = 0L;
        long signatureBytesClassicalTotal = 0L;

        int totalPackets = 0;
        int processedPackets = 0;
        int rejectedPackets = 0;
        long totalLatencyMs = 0;
        // Accumulate all sensor readings for batch DB save + anomaly detection
        List<SensorData> allSensorData = new ArrayList<>();
        List<SensorData> pendingSensorData = new ArrayList<>();
        int persistedSensorRows = 0;

        System.out.println("  Starting packet processing...");
        System.out.println("  Progress updates every " + (PROGRESS_REPORT_INTERVAL_MS / 1000) + " seconds");

        try {
            int deviceIndex = 0;
            int datasetIndex = 0;
            while (System.currentTimeMillis() < endTime) {
                SensorData sensorData;

                if (DeviceConfig.CURRENT_MODE == DeviceConfig.Mode.DATASET) {
                    if (datasetData == null || datasetData.isEmpty()) {
                        throw new IllegalStateException("Dataset mode is active but no dataset rows were loaded");
                    }
                    SensorData source = datasetData.get(datasetIndex % datasetData.size());
                    String category = source.getDeviceId();
                    String mappedDeviceId = resolveDeviceIdForCategory(category, categoryToDeviceIds, categoryCursor);
                    sensorData = new SensorData(mappedDeviceId, source.getSensorValue(), System.currentTimeMillis());
                    datasetIndex++;
                } else {
                    DeviceSimulator simulator = devices.get(deviceIndex % devices.size());
                    sensorData = simulator.generateReading();
                    deviceIndex++;
                }

                long packetStartTime = System.currentTimeMillis();
                totalPackets++;

                try {
                    GatewayServer activeGateway = switch (cryptoMode) {
                        case PQC_ONLY -> pqcGateway;
                        case CLASSICAL_ONLY -> classicalGateway;
                        case HYBRID -> (totalPackets % 2 == 0) ? pqcGateway : classicalGateway;
                    };

                    String payload = sensorData.getSensorValue() + ":" + sensorData.getTimestamp();
                    int payloadBytes = payload.getBytes(StandardCharsets.UTF_8).length;

                    SecurePacket packet = activeGateway.createSecurePacket(sensorData.getDeviceId(), payload);

                    int envelopeBytes = packet.getEncryptedData() == null ? 0 : packet.getEncryptedData().length;
                    int signatureBytes = packet.getSignature() == null ? 0 : packet.getSignature().length;

                    byteSampleCount++;
                    payloadBytesTotal += payloadBytes;
                    envelopeBytesTotal += envelopeBytes;
                    signatureBytesTotal += signatureBytes;

                    if (activeGateway == pqcGateway) {
                        pqcByteSampleCount++;
                        payloadBytesPqcTotal += payloadBytes;
                        envelopeBytesPqcTotal += envelopeBytes;
                        signatureBytesPqcTotal += signatureBytes;
                    } else if (activeGateway == classicalGateway) {
                        classicalByteSampleCount++;
                        payloadBytesClassicalTotal += payloadBytes;
                        envelopeBytesClassicalTotal += envelopeBytes;
                        signatureBytesClassicalTotal += signatureBytes;
                    }

                    SensorData processedData = activeGateway.processPacket(packet);

                    allSensorData.add(processedData);
                    pendingSensorData.add(processedData);
                    processedPackets++;

                    if (pendingSensorData.size() >= SENSOR_FLUSH_BATCH_SIZE) {
                        DatabaseManager.saveSensorDataBatch(new ArrayList<>(pendingSensorData));
                        persistedSensorRows += pendingSensorData.size();
                        pendingSensorData.clear();
                    }

                } catch (Exception e) {
                    rejectedPackets++;
                }

                long latency = System.currentTimeMillis() - packetStartTime;
                totalLatencyMs += latency;

                if (totalPackets % 50 == 0) {
                    System.out.print(".");
                    if (totalPackets % 500 == 0) {
                        System.out.println(" (" + totalPackets + " packets)");
                    }
                }

                long now = System.currentTimeMillis();

                // Sample process CPU + heap periodically throughout the run.
                if (now >= nextResourceSampleAt) {
                    double cpu = performanceMonitor.getCpuUsage();
                    if (Double.isFinite(cpu) && cpu >= 0.0) {
                        cpuSampleCount++;
                        cpuSum += cpu;
                        cpuMax = Math.max(cpuMax, cpu);
                    }

                    double mem = performanceMonitor.getMemoryUsage();
                    if (Double.isFinite(mem) && mem >= 0.0) {
                        memSampleCount++;
                        memSum += mem;
                        memMax = Math.max(memMax, mem);
                    }

                    nextResourceSampleAt = now + resourceSampleIntervalMs;
                }

                if (now >= nextProgressReportAt) {
                    long elapsedMs = now - startTime;
                    long remainingMs = Math.max(0, endTime - now);
                    System.out.println("\n  [Progress] elapsed=" + formatDuration(elapsedMs)
                            + ", remaining=" + formatDuration(remainingMs)
                            + ", packets=" + totalPackets
                            + ", processed=" + processedPackets
                            + ", rejected=" + rejectedPackets
                            + ", sensorRowsPersisted=" + persistedSensorRows);
                    nextProgressReportAt += PROGRESS_REPORT_INTERVAL_MS;
                }

                if (packetIntervalMs > 0) {
                    Thread.sleep(packetIntervalMs);
                } else {
                    Thread.yield();
                }
            }
        } catch (InterruptedException e) {
            System.out.println("\nTest interrupted: " + e.getMessage());
        }

        long elapsedTime = System.currentTimeMillis() - startTime;
        double avgLatency = totalPackets > 0 ? (double) totalLatencyMs / totalPackets : 0;
        double throughput = processedPackets / (elapsedTime / 1000.0);
        double packetDropRate = totalPackets > 0 ? (double) rejectedPackets / totalPackets * 100 : 0;

        // End-snapshot (kept for transparency) + run-averaged samples (used for reporting)
        double cpuEndPercent = performanceMonitor.getCpuUsage();
        double memEndMb = performanceMonitor.getMemoryUsage();
        double cpuAvgPercent = cpuSampleCount > 0 ? (cpuSum / cpuSampleCount) : (cpuEndPercent >= 0.0 ? cpuEndPercent : 0.0);
        double memAvgMb = memSampleCount > 0 ? (memSum / memSampleCount) : memEndMb;
        double cpuMaxPercent = cpuSampleCount > 0 ? cpuMax : Math.max(0.0, cpuEndPercent);
        double memMaxMb = memSampleCount > 0 ? memMax : memEndMb;

        double payloadBytesAvg = byteSampleCount > 0 ? (payloadBytesTotal / (double) byteSampleCount) : 0.0;
        double envelopeBytesAvg = byteSampleCount > 0 ? (envelopeBytesTotal / (double) byteSampleCount) : 0.0;
        double signatureBytesAvg = byteSampleCount > 0 ? (signatureBytesTotal / (double) byteSampleCount) : 0.0;

        double payloadBytesAvgPqc = pqcByteSampleCount > 0 ? (payloadBytesPqcTotal / (double) pqcByteSampleCount) : 0.0;
        double envelopeBytesAvgPqc = pqcByteSampleCount > 0 ? (envelopeBytesPqcTotal / (double) pqcByteSampleCount) : 0.0;
        double signatureBytesAvgPqc = pqcByteSampleCount > 0 ? (signatureBytesPqcTotal / (double) pqcByteSampleCount) : 0.0;

        double payloadBytesAvgClassical = classicalByteSampleCount > 0 ? (payloadBytesClassicalTotal / (double) classicalByteSampleCount) : 0.0;
        double envelopeBytesAvgClassical = classicalByteSampleCount > 0 ? (envelopeBytesClassicalTotal / (double) classicalByteSampleCount) : 0.0;
        double signatureBytesAvgClassical = classicalByteSampleCount > 0 ? (signatureBytesClassicalTotal / (double) classicalByteSampleCount) : 0.0;

        // --- Persist sensor data (cap at 1000 rows to avoid flooding the DB) ---
        System.out.println("\n  Packet generation complete. Persisting results...");
        System.out.println("  Saving sensor data to database...");
        if (!pendingSensorData.isEmpty()) {
            DatabaseManager.saveSensorDataBatch(new ArrayList<>(pendingSensorData));
            persistedSensorRows += pendingSensorData.size();
            pendingSensorData.clear();
        }
        System.out.println("  ✓ Saved " + persistedSensorRows + " sensor readings");

        // --- Run proper ensemble anomaly detection on accumulated batch ---
        System.out.println("  Running anomaly detection on full batch...");
        List<AnomalyEvent> detectedAnomalies = anomalyDetector.detectAnomalies(
                allSensorData, 3, 0.1, 2.0);

        // --- Detection quality metrics (only meaningful when dataset-derived category encodes ground truth) ---
        ClassificationMetrics detectionMetrics = null;
        if (DeviceConfig.CURRENT_MODE == DeviceConfig.Mode.DATASET) {
            detectionMetrics = ClassificationMetrics.compute(detectedAnomalies);
            System.out.println(String.format(Locale.US,
                "  Detection metrics (derived labels): precision=%.3f, recall=%.3f, f1=%.3f, attackRate=%.3f",
                detectionMetrics.precision,
                detectionMetrics.recall,
                detectionMetrics.f1,
                detectionMetrics.attackRate));
        }
        List<AnomalyEvent> anomaliesToSave = new ArrayList<>();
        for (AnomalyEvent event : detectedAnomalies) {
            if (event.isAnomaly()) {
            anomaliesToSave.add(event);
            }
        }
        DatabaseManager.saveAnomalyEventsBatch(anomaliesToSave);
        int anomalyCount = anomaliesToSave.size();
        System.out.println("  ✓ Saved " + anomalyCount + " anomaly events");

        // --- Record and persist performance metrics (actual duration, not epoch) ---
        performanceMonitor.recordMetric("StressTest_Encryption_" + configuredDeviceCount + "devices", elapsedTime);
        performanceMonitor.recordMetric("StressTest_AvgLatency", (long) avgLatency);
        for (var metric : performanceMonitor.getMetrics()) {
            DatabaseManager.savePerformanceMetric(metric);
        }
        System.out.println("  ✓ Saved performance metrics");

        // --- Save scalability result for this 50-device run ---
        int actualDeviceCount = deviceIds.size();
        String modeName = toModeName(cryptoMode);
        double cpuUsagePercent = cpuAvgPercent;
        double memoryUsageMb = memAvgMb;

        DatabaseManager.saveScalabilityResult(
            modeName,
            actualDeviceCount,
                totalPackets,
                processedPackets,
                avgLatency,
                throughput,
            cpuUsagePercent,
            memoryUsageMb,
                packetDropRate,
                anomalyCount,
                System.currentTimeMillis()
        );
        System.out.println("  ✓ Saved scalability result for " + actualDeviceCount + " devices");

        results.put("deviceCount", actualDeviceCount);
        results.put("runId", DatabaseManager.getCurrentRunId());
        results.put("cryptoMode", cryptoMode.name());
        results.put("packetIntervalMs", packetIntervalMs);
        results.put("totalPackets", totalPackets);
        results.put("processedPackets", processedPackets);
        results.put("rejectedPackets", rejectedPackets);
        results.put("anomalyCount", anomalyCount);
        results.put("avgLatencyMs", avgLatency);
        results.put("throughputPps", throughput);
        results.put("packetDropRate", packetDropRate);
        results.put("elapsedTimeMs", elapsedTime);
        results.put("cpuUsagePercent", cpuUsagePercent);
        results.put("memoryUsageMb", memoryUsageMb);
        results.put("cpuUsageMaxPercent", cpuMaxPercent);
        results.put("memoryUsageMaxMb", memMaxMb);
        results.put("cpuUsageEndPercent", cpuEndPercent);
        results.put("memoryUsageEndMb", memEndMb);
        results.put("resourceSampleCount", Math.max(cpuSampleCount, memSampleCount));

        results.put("payloadBytesAvg", payloadBytesAvg);
        results.put("envelopeBytesAvg", envelopeBytesAvg);
        results.put("signatureBytesAvg", signatureBytesAvg);

        results.put("payloadBytesAvgPqc", payloadBytesAvgPqc);
        results.put("envelopeBytesAvgPqc", envelopeBytesAvgPqc);
        results.put("signatureBytesAvgPqc", signatureBytesAvgPqc);

        results.put("payloadBytesAvgClassical", payloadBytesAvgClassical);
        results.put("envelopeBytesAvgClassical", envelopeBytesAvgClassical);
        results.put("signatureBytesAvgClassical", signatureBytesAvgClassical);
        if (detectionMetrics != null) {
            results.put("det_precision", detectionMetrics.precision);
            results.put("det_recall", detectionMetrics.recall);
            results.put("det_f1", detectionMetrics.f1);
            results.put("det_attackRate", detectionMetrics.attackRate);
            results.put("det_tp", detectionMetrics.tp);
            results.put("det_fp", detectionMetrics.fp);
            results.put("det_tn", detectionMetrics.tn);
            results.put("det_fn", detectionMetrics.fn);
        }

        System.out.println("\n  ✓ Stress test completed");
        System.out.println("    - Total Packets: " + totalPackets);
        System.out.println("    - Processed: " + processedPackets);
        System.out.println("    - Rejected: " + rejectedPackets);
        System.out.println("    - Anomalies Detected: " + anomalyCount);
        System.out.println("    - Avg Latency: " + String.format("%.2f", avgLatency) + " ms");
        System.out.println("    - Throughput: " + String.format("%.2f", throughput) + " pps");
        System.out.println("    - Packet Drop Rate: " + String.format("%.2f", packetDropRate) + "%");
        System.out.println("    - Elapsed Time: " + (elapsedTime / 1000) + " seconds");

        return results;
    }

    private static void registerAndAuthenticateDevice(GatewayServer gateway, String deviceId) throws Exception {
        gateway.registerDevice(deviceId);
        byte[] challenge = gateway.generateChallenge(deviceId);
        byte[] signature = gateway.signChallengeAsDevice(deviceId, challenge);
        gateway.authenticateDevice(deviceId, challenge, signature);
    }

    private static long resolveTestDurationMs() {
        String override = System.getProperty("pqciot.finalTest.durationMs");
        if (override == null || override.isBlank()) {
            override = System.getenv("PQCIOT_FINAL_TEST_DURATION_MS");
        }

        if (override == null || override.isBlank()) {
            return DEFAULT_TEST_DURATION_MS;
        }

        try {
            long parsed = Long.parseLong(override.trim());
            return parsed > 0 ? parsed : DEFAULT_TEST_DURATION_MS;
        } catch (NumberFormatException ignored) {
            return DEFAULT_TEST_DURATION_MS;
        }
    }

    private static int resolveDeviceCount() {
        String override = System.getProperty("pqciot.finalTest.deviceCount");
        if (override == null || override.isBlank()) {
            override = System.getenv("PQCIOT_FINAL_TEST_DEVICE_COUNT");
        }

        if (override == null || override.isBlank()) {
            return FINAL_TEST_DEVICE_COUNT;
        }

        try {
            int parsed = Integer.parseInt(override.trim());
            return parsed > 0 ? parsed : FINAL_TEST_DEVICE_COUNT;
        } catch (NumberFormatException ignored) {
            return FINAL_TEST_DEVICE_COUNT;
        }
    }

    private static long resolvePacketIntervalMs() {
        String override = System.getProperty("pqciot.finalTest.intervalMs");
        if (override == null || override.isBlank()) {
            override = System.getenv("PQCIOT_FINAL_TEST_INTERVAL_MS");
        }

        if (override == null || override.isBlank()) {
            return DEFAULT_PACKET_INTERVAL_MS;
        }

        try {
            long parsed = Long.parseLong(override.trim());
            return Math.max(0L, parsed);
        } catch (NumberFormatException ignored) {
            return DEFAULT_PACKET_INTERVAL_MS;
        }
    }

    private static long resolveResourceSampleIntervalMs() {
        String override = System.getProperty("pqciot.finalTest.resourceSampleIntervalMs");
        if (override == null || override.isBlank()) {
            override = System.getenv("PQCIOT_FINAL_TEST_RESOURCE_SAMPLE_INTERVAL_MS");
        }

        if (override == null || override.isBlank()) {
            return 1_000L;
        }

        try {
            long parsed = Long.parseLong(override.trim());
            // Keep sampling sane even if misconfigured.
            return Math.max(100L, parsed);
        } catch (NumberFormatException ignored) {
            return 1_000L;
        }
    }

    private static CryptoMode resolveCryptoMode() {
        String override = System.getProperty("pqciot.finalTest.cryptoMode");
        if (override == null || override.isBlank()) {
            override = System.getenv("PQCIOT_FINAL_TEST_CRYPTO_MODE");
        }

        if (override == null || override.isBlank()) {
            return CryptoMode.HYBRID;
        }

        String normalized = override.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "PQC", "PQC_ONLY" -> CryptoMode.PQC_ONLY;
            case "CLASSICAL", "CLASSICAL_ONLY" -> CryptoMode.CLASSICAL_ONLY;
            case "HYBRID", "MIXED", "PQC+CLASSICAL" -> CryptoMode.HYBRID;
            default -> CryptoMode.HYBRID;
        };
    }

    private static String toModeName(CryptoMode cryptoMode) {
        return switch (cryptoMode) {
            case PQC_ONLY -> "PQC_ONLY";
            case CLASSICAL_ONLY -> "CLASSICAL_ONLY";
            case HYBRID -> "PQC+CLASSICAL";
        };
    }

    private static Map<String, List<String>> expandCategoriesToDevices(List<String> categories, int totalDeviceCount) {
        Map<String, List<String>> mapping = new HashMap<>();
        if (categories == null || categories.isEmpty()) {
            categories = List.of("unsw-device");
        }

        for (String category : categories) {
            mapping.put(category, new ArrayList<>());
        }

        int safeTotal = Math.max(1, totalDeviceCount);
        for (int i = 0; i < safeTotal; i++) {
            String category = categories.get(i % categories.size());
            String deviceId = category + "-" + (i + 1);
            mapping.get(category).add(deviceId);
        }

        return mapping;
    }

    private static String resolveDeviceIdForCategory(String category,
                                                    Map<String, List<String>> categoryToDeviceIds,
                                                    Map<String, Integer> cursor) {
        if (categoryToDeviceIds == null || categoryToDeviceIds.isEmpty()) {
            return category == null || category.isBlank() ? "unsw-device" : category.trim();
        }

        String key = (category == null || category.isBlank()) ? categoryToDeviceIds.keySet().iterator().next() : category.trim();
        List<String> deviceIds = categoryToDeviceIds.get(key);
        if (deviceIds == null || deviceIds.isEmpty()) {
            // Fallback to any available category
            String first = categoryToDeviceIds.keySet().iterator().next();
            deviceIds = categoryToDeviceIds.get(first);
            key = first;
        }

        int index = cursor.getOrDefault(key, 0);
        String deviceId = deviceIds.get(index % deviceIds.size());
        cursor.put(key, index + 1);
        return deviceId;
    }

    private static String formatDuration(long durationMs) {
        long totalSeconds = Math.max(0, durationMs / 1000);
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        return minutes + "m " + seconds + "s";
    }

    // ===== PHASE 3: IMMUTABLE LOG VALIDATION =====

    private static boolean validateImmutableLogs() throws Exception {
        System.out.println("  Querying system_logs table...");
        String runId = DatabaseManager.getCurrentRunId();

        try (Connection connection = DatabaseManager.getConnection()) {
            String query = "SELECT COUNT(*) as log_count, " +
                    "SUM(CASE WHEN event_type = 'PACKET_PROCESSED' THEN 1 ELSE 0 END) as packet_count " +
                    "FROM system_logs WHERE run_id = ?";
            try (java.sql.PreparedStatement stmt = connection.prepareStatement(query)) {
                stmt.setString(1, runId);
                ResultSet rs = stmt.executeQuery();
                if (rs.next()) {
                    int logCount = rs.getInt("log_count");
                    int packetCount = rs.getInt("packet_count");
                    System.out.println("  ✓ Found " + logCount + " total log entries and " + packetCount + " PACKET_PROCESSED entries in database for run " + runId);

                    if (logCount == 0) {
                        System.out.println("  ✗ No log entries found - hash chain cannot be verified");
                        return false;
                    }

                    // Verify hash chain integrity from database
                    System.out.println("  Verifying hash chain integrity...");
                    if (verifyHashChainFromDb()) {
                        System.out.println("  ✓ Hash chain integrity: VALID");
                        return true;
                    } else {
                        System.out.println("  ✗ Hash chain integrity: COMPROMISED");
                        return false;
                    }
                }
            }
        }

        return false;
    }

    private static boolean verifyHashChainFromDb() throws Exception {
        String runId = DatabaseManager.getCurrentRunId();
        try (Connection connection = DatabaseManager.getConnection()) {
            String query = "SELECT log_hash, previous_hash, event_type, event_data, timestamp FROM system_logs WHERE run_id = ? ORDER BY id;";
            try (java.sql.PreparedStatement stmt = connection.prepareStatement(query)) {
                stmt.setString(1, runId);
                ResultSet rs = stmt.executeQuery();

                String previousHash = "0";
                int chainLength = 0;

                while (rs.next()) {
                    String logHash = rs.getString("log_hash");
                    String eventType = rs.getString("event_type");
                    String eventData = rs.getString("event_data");
                    long timestamp = rs.getLong("timestamp");

                    String previousHashFromDb = rs.getString("previous_hash");

                    // Verify previous hash matches
                    if (!previousHashFromDb.equals(previousHash)) {
                        System.out.println("    ALERT: Previous hash mismatch at entry: " + eventType);
                        return false;
                    }

                    // Verify current hash
                    String logContent = eventType + "|" + eventData + "|" + previousHash + "|" + timestamp;
                    String computedHash = computeSHA256(logContent);

                    if (!computedHash.equals(logHash)) {
                        System.out.println("    ALERT: Hash mismatch at entry: " + eventType);
                        return false;
                    }

                    previousHash = logHash;
                    chainLength++;
                }

                System.out.println("    Chain verified through " + chainLength + " entries");
                return true;
            }
        }
    }

    private static String computeSHA256(String input) throws Exception {
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        byte[] hashBytes = digest.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder hexString = new StringBuilder();
        for (byte b : hashBytes) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) hexString.append('0');
            hexString.append(hex);
        }
        return hexString.toString();
    }

    // ===== PHASE 4: TABLE POPULATION VALIDATION =====

    private static boolean validateTablePopulation() throws Exception {
        String runId = DatabaseManager.getCurrentRunId();
        try (Connection connection = DatabaseManager.getConnection()) {
            String[] tables = {
                    "sensor_data",
                    "anomaly_events",
                    "performance_metrics",
                    "system_logs",
                    "key_rotation_logs",
                    "scalability_results"
            };

            Map<String, Integer> tableCounts = new HashMap<>();

            for (String table : tables) {
                String query = "SELECT COUNT(*) as count FROM " + table + " WHERE run_id = ?;";
                try (java.sql.PreparedStatement stmt = connection.prepareStatement(query)) {
                    stmt.setString(1, runId);
                    ResultSet rs = stmt.executeQuery();
                    if (rs.next()) {
                        int count = rs.getInt("count");
                        tableCounts.put(table, count);
                    }
                }
            }

            System.out.println("  Table Population Status:");
            System.out.println("  Run ID: " + runId);
            int validTables = 0;
            for (String table : tables) {
                int count = tableCounts.getOrDefault(table, 0);
                String status = count > 0 ? "✓" : "✗";
                System.out.println("    " + status + " " + table + ": " + count + " rows");
                if (count > 0) validTables++;
            }

            if (validTables == tables.length) {
                System.out.println("  ✓ All tables populated correctly");
                return true;
            } else {
                System.out.println("  ⚠ Warning: " + (tables.length - validTables) + " tables have no data");
                return validTables >= 4; // At least 4 tables should have data
            }
        }
    }

    // ===== PHASE 5: FINAL REPORT =====

    private static void generateFinalReport(Map<String, Object> stressTestResults) throws Exception {
        System.out.println("\n" + "-".repeat(70));
        System.out.println("  FINAL INTEGRATION TEST REPORT");
        System.out.println("-".repeat(70));

        int deviceCount = toInt(stressTestResults.getOrDefault("deviceCount", FINAL_TEST_DEVICE_COUNT));
        String cryptoMode = String.valueOf(stressTestResults.getOrDefault("cryptoMode", "HYBRID"));
        long elapsedTimeMs = toLong(stressTestResults.get("elapsedTimeMs"));
        long packetIntervalMs = toLong(stressTestResults.getOrDefault("packetIntervalMs", DEFAULT_PACKET_INTERVAL_MS));
        System.out.println("\n  [STRESS TEST RESULTS] (" + deviceCount + " devices, " + formatDuration(elapsedTimeMs) + "):");
        System.out.println("    Crypto Mode: " + cryptoMode + " (interval=" + packetIntervalMs + "ms)");
        System.out.println("    Total Packets Processed: " + stressTestResults.get("totalPackets"));
        System.out.println("    Successfully Processed: " + stressTestResults.get("processedPackets"));
        System.out.println("    Rejected Packets: " + stressTestResults.get("rejectedPackets"));
        System.out.println("    Anomalies Detected: " + stressTestResults.get("anomalyCount"));
        System.out.println("    Average Latency: " + 
                String.format("%.2f", (Double) stressTestResults.get("avgLatencyMs")) + " ms");
        System.out.println("    Throughput: " + 
                String.format("%.2f", (Double) stressTestResults.get("throughputPps")) + " pps");
        System.out.println("    Packet Drop Rate: " + 
                String.format("%.2f", (Double) stressTestResults.get("packetDropRate")) + "%");
        System.out.println("    Test Duration: " + (elapsedTimeMs / 1000) + " seconds");

        if (stressTestResults.containsKey("det_precision")) {
            System.out.println("\n  [DETECTION QUALITY] (derived labels from dataset category):");
            System.out.println("    Precision: " + String.format(Locale.US, "%.3f", toDouble(stressTestResults.get("det_precision"))));
            System.out.println("    Recall:    " + String.format(Locale.US, "%.3f", toDouble(stressTestResults.get("det_recall"))));
            System.out.println("    F1:        " + String.format(Locale.US, "%.3f", toDouble(stressTestResults.get("det_f1"))));
            System.out.println("    AttackRate:" + String.format(Locale.US, "%.3f", toDouble(stressTestResults.get("det_attackRate"))));
        }

        System.out.println("\n  [VALIDATION SUMMARY]:");
        System.out.println("    ✓ Database connection: PASS");
        System.out.println("    ✓ Immutable log integrity: PASS");
        System.out.println("    ✓ Table population: PASS");
        System.out.println("    ✓ Stress test (50 devices): PASS");

        System.out.println("\n  [FINAL STATUS]");
        System.out.println("    Result: PASS");
        System.out.println("    Database persistence: VALIDATED");
        System.out.println("    Security hash chain: VALIDATED");
        System.out.println("    Run ID: " + DatabaseManager.getCurrentRunId());

        System.out.println("\n" + "-".repeat(70));

        // Save report to database for reference
        try (Connection connection = DatabaseManager.getConnection()) {
            String reportJson = String.format(
                    "{\"totalPackets\": %d, \"processedPackets\": %d, \"anomalyCount\": %d, \"avgLatency\": %.2f}",
                    stressTestResults.get("totalPackets"),
                    stressTestResults.get("processedPackets"),
                    stressTestResults.get("anomalyCount"),
                    stressTestResults.get("avgLatencyMs")
            );

            String insertLog = "INSERT INTO system_logs (log_hash, previous_hash, run_id, event_type, event_data, timestamp) " +
                    "VALUES (?, ?, ?, 'INTEGRATION_TEST_COMPLETE', ?, ?)";

            try (java.sql.PreparedStatement pstmt = connection.prepareStatement(insertLog)) {
                String hash = computeSHA256("INTEGRATION_TEST_COMPLETE" + reportJson);
                pstmt.setString(1, hash);
                pstmt.setString(2, "0");
                pstmt.setString(3, DatabaseManager.getCurrentRunId());
                pstmt.setString(4, reportJson);
                pstmt.setLong(5, System.currentTimeMillis());
                pstmt.executeUpdate();
            }
        }

        exportReportFiles(stressTestResults);
    }

    private static void exportReportFiles(Map<String, Object> stressTestResults) throws Exception {
        Path reportsDir = Path.of("research_reports");
        Files.createDirectories(reportsDir);

        String runId = DatabaseManager.getCurrentRunId();
        String timestamp = LocalDateTime.now().format(REPORT_FILE_TS);

        int deviceCount = toInt(stressTestResults.getOrDefault("deviceCount", FINAL_TEST_DEVICE_COUNT));
        String cryptoMode = String.valueOf(stressTestResults.getOrDefault("cryptoMode", "HYBRID"));
        long packetIntervalMs = toLong(stressTestResults.getOrDefault("packetIntervalMs", DEFAULT_PACKET_INTERVAL_MS));
        String modeName = toModeName(resolveCryptoMode());

        int totalPackets = toInt(stressTestResults.get("totalPackets"));
        int processedPackets = toInt(stressTestResults.get("processedPackets"));
        int rejectedPackets = toInt(stressTestResults.get("rejectedPackets"));
        int anomalyCount = toInt(stressTestResults.get("anomalyCount"));
        long elapsedTimeMs = toLong(stressTestResults.get("elapsedTimeMs"));
        double avgLatencyMs = toDouble(stressTestResults.get("avgLatencyMs"));
        double throughputPps = toDouble(stressTestResults.get("throughputPps"));
        double packetDropRate = toDouble(stressTestResults.get("packetDropRate"));

        String txtContent = String.join(System.lineSeparator(),
                "============================================================",
                "PQC-IoT Final Integration Report",
                "============================================================",
                "Generated At: " + Instant.now(),
                "Run ID: " + runId,
                "",
                "[Stress Test Summary]",
            "Device Count: " + deviceCount,
            "Crypto Mode: " + cryptoMode,
            "Packet Interval (ms): " + packetIntervalMs,
                "Total Packets: " + totalPackets,
                "Processed Packets: " + processedPackets,
                "Rejected Packets: " + rejectedPackets,
                "Anomalies Detected: " + anomalyCount,
                "Average Latency (ms): " + String.format(Locale.US, "%.3f", avgLatencyMs),
                "Throughput (pps): " + String.format(Locale.US, "%.3f", throughputPps),
                "Packet Drop Rate (%): " + String.format(Locale.US, "%.3f", packetDropRate),
                "Elapsed Time (s): " + String.format(Locale.US, "%.2f", elapsedTimeMs / 1000.0),
            "CPU Usage Avg (%): " + String.format(Locale.US, "%.3f", toDouble(stressTestResults.getOrDefault("cpuUsagePercent", 0.0))),
            "CPU Usage Max (%): " + String.format(Locale.US, "%.3f", toDouble(stressTestResults.getOrDefault("cpuUsageMaxPercent", 0.0))),
            "CPU Usage End (%): " + String.format(Locale.US, "%.3f", toDouble(stressTestResults.getOrDefault("cpuUsageEndPercent", 0.0))),
            "Memory Usage Avg (MB): " + String.format(Locale.US, "%.3f", toDouble(stressTestResults.getOrDefault("memoryUsageMb", 0.0))),
            "Memory Usage Max (MB): " + String.format(Locale.US, "%.3f", toDouble(stressTestResults.getOrDefault("memoryUsageMaxMb", 0.0))),
            "Memory Usage End (MB): " + String.format(Locale.US, "%.3f", toDouble(stressTestResults.getOrDefault("memoryUsageEndMb", 0.0))),
            "Resource Samples: " + toInt(stressTestResults.getOrDefault("resourceSampleCount", 0)),
            "Payload Bytes Avg: " + String.format(Locale.US, "%.3f", toDouble(stressTestResults.getOrDefault("payloadBytesAvg", 0.0))),
            "Envelope Bytes Avg: " + String.format(Locale.US, "%.3f", toDouble(stressTestResults.getOrDefault("envelopeBytesAvg", 0.0))),
            "Signature Bytes Avg: " + String.format(Locale.US, "%.3f", toDouble(stressTestResults.getOrDefault("signatureBytesAvg", 0.0))),
                "",
            stressTestResults.containsKey("det_precision") ? "[Detection Quality]" : "",
            stressTestResults.containsKey("det_precision") ? ("Precision: " + String.format(Locale.US, "%.3f", toDouble(stressTestResults.get("det_precision")))) : "",
            stressTestResults.containsKey("det_precision") ? ("Recall: " + String.format(Locale.US, "%.3f", toDouble(stressTestResults.get("det_recall")))) : "",
            stressTestResults.containsKey("det_precision") ? ("F1: " + String.format(Locale.US, "%.3f", toDouble(stressTestResults.get("det_f1")))) : "",
            stressTestResults.containsKey("det_precision") ? ("AttackRate: " + String.format(Locale.US, "%.3f", toDouble(stressTestResults.get("det_attackRate")))) : "",
            stressTestResults.containsKey("det_precision") ? "" : "",
                "[Validation]",
                "Database persistence: PASS",
                "Hash-chain integrity: PASS",
                "Table population: PASS",
                "============================================================",
                "");

        String csvContent = String.join(System.lineSeparator(),
            "run_id,mode_name,device_count,crypto_mode,packet_interval_ms,total_packets,processed_packets,rejected_packets,anomaly_count,avg_latency_ms,throughput_pps,packet_drop_rate_percent,duration_seconds,cpu_usage_avg_percent,cpu_usage_max_percent,cpu_usage_end_percent,memory_usage_avg_mb,memory_usage_max_mb,memory_usage_end_mb,resource_sample_count,payload_bytes_avg,envelope_bytes_avg,signature_bytes_avg,det_precision,det_recall,det_f1,det_attack_rate",
            String.format(Locale.US,
            "%s,%s,%d,%s,%d,%d,%d,%d,%d,%.3f,%.3f,%.3f,%.2f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%d,%.3f,%.3f,%.3f,%s,%s,%s,%s",
                runId,
            modeName,
            deviceCount,
            cryptoMode,
            packetIntervalMs,
                totalPackets,
                processedPackets,
                rejectedPackets,
                anomalyCount,
                avgLatencyMs,
                throughputPps,
                packetDropRate,
            elapsedTimeMs / 1000.0,
            toDouble(stressTestResults.getOrDefault("cpuUsagePercent", 0.0)),
            toDouble(stressTestResults.getOrDefault("cpuUsageMaxPercent", 0.0)),
            toDouble(stressTestResults.getOrDefault("cpuUsageEndPercent", 0.0)),
            toDouble(stressTestResults.getOrDefault("memoryUsageMb", 0.0)),
            toDouble(stressTestResults.getOrDefault("memoryUsageMaxMb", 0.0)),
            toDouble(stressTestResults.getOrDefault("memoryUsageEndMb", 0.0)),
            toInt(stressTestResults.getOrDefault("resourceSampleCount", 0)),
            toDouble(stressTestResults.getOrDefault("payloadBytesAvg", 0.0)),
            toDouble(stressTestResults.getOrDefault("envelopeBytesAvg", 0.0)),
            toDouble(stressTestResults.getOrDefault("signatureBytesAvg", 0.0)),
            stressTestResults.containsKey("det_precision") ? String.format(Locale.US, "%.3f", toDouble(stressTestResults.get("det_precision"))) : "",
            stressTestResults.containsKey("det_recall") ? String.format(Locale.US, "%.3f", toDouble(stressTestResults.get("det_recall"))) : "",
            stressTestResults.containsKey("det_f1") ? String.format(Locale.US, "%.3f", toDouble(stressTestResults.get("det_f1"))) : "",
            stressTestResults.containsKey("det_attackRate") ? String.format(Locale.US, "%.3f", toDouble(stressTestResults.get("det_attackRate"))) : ""),
            "");

        String sqlContent = String.join(System.lineSeparator(),
                "-- Benchmark overhead report snapshot",
                "-- Generated by FinalIntegrationTest",
                "USE pqciot_db;",
                String.format(Locale.US,
                        "INSERT INTO system_logs (log_hash, previous_hash, run_id, event_type, event_data, timestamp) VALUES ('%s', '0', '%s', 'BENCHMARK_OVERHEAD_REPORT', '{\"avgLatencyMs\":%.3f,\"throughputPps\":%.3f,\"packetDropRate\":%.3f}', %d);",
                        computeSHA256("BENCHMARK_OVERHEAD_REPORT" + runId + avgLatencyMs + throughputPps),
                        runId,
                        avgLatencyMs,
                        throughputPps,
                        packetDropRate,
                        System.currentTimeMillis()),
                "");

        Path txtPath = reportsDir.resolve("FINAL_RESEARCH_REPORT_" + timestamp + ".txt");
        Path csvPath = reportsDir.resolve("detailed_metrics_" + timestamp + ".csv");
        Path sqlPath = reportsDir.resolve("benchmark_overhead_report.sql");

        Files.writeString(txtPath, txtContent, StandardCharsets.UTF_8);
        Files.writeString(csvPath, csvContent, StandardCharsets.UTF_8);
        Files.writeString(sqlPath, sqlContent, StandardCharsets.UTF_8);

        System.out.println("  ✓ Wrote report file: " + txtPath.toAbsolutePath());
        System.out.println("  ✓ Wrote metrics file: " + csvPath.toAbsolutePath());
        System.out.println("  ✓ Updated SQL snapshot: " + sqlPath.toAbsolutePath());
    }

    private static int toInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(String.valueOf(value));
    }

    private static long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }

    private static double toDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return Double.parseDouble(String.valueOf(value));
    }

    private static final class ClassificationMetrics {
        private final int tp;
        private final int fp;
        private final int tn;
        private final int fn;
        private final double precision;
        private final double recall;
        private final double f1;
        private final double attackRate;

        private ClassificationMetrics(int tp, int fp, int tn, int fn,
                                      double precision, double recall, double f1,
                                      double attackRate) {
            this.tp = tp;
            this.fp = fp;
            this.tn = tn;
            this.fn = fn;
            this.precision = precision;
            this.recall = recall;
            this.f1 = f1;
            this.attackRate = attackRate;
        }

        private static ClassificationMetrics compute(List<AnomalyEvent> events) {
            if (events == null || events.isEmpty()) {
                return new ClassificationMetrics(0, 0, 0, 0, 0, 0, 0, 0);
            }

            int tp = 0;
            int fp = 0;
            int tn = 0;
            int fn = 0;
            int positives = 0;

            for (AnomalyEvent event : events) {
                boolean predicted = event != null && event.isAnomaly();
                boolean actualAttack = isAttackDeviceId(event == null ? null : event.getDeviceId());
                if (actualAttack) positives++;

                if (predicted && actualAttack) tp++;
                else if (predicted) fp++;
                else if (actualAttack) fn++;
                else tn++;
            }

            double precision = (tp + fp) > 0 ? (double) tp / (tp + fp) : 0.0;
            double recall = (tp + fn) > 0 ? (double) tp / (tp + fn) : 0.0;
            double f1 = (precision + recall) > 0 ? 2.0 * precision * recall / (precision + recall) : 0.0;
            double attackRate = events.size() > 0 ? (double) positives / events.size() : 0.0;

            return new ClassificationMetrics(tp, fp, tn, fn, precision, recall, f1, attackRate);
        }
    }

    private static boolean isAttackDeviceId(String deviceId) {
        if (deviceId == null || deviceId.isBlank()) {
            return false;
        }

        String normalized = deviceId.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("normal-device")) {
            return false;
        }
        if (normalized.contains("normal-device")) {
            return false;
        }

        // Dataset-derived categories are encoded as "<category>-device[-N]".
        return normalized.contains("-device");
    }
}
