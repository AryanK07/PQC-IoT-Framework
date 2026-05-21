package com.aryan.pqciotframework.performance;

import com.aryan.pqciotframework.anomaly.AnomalyDetector;
import com.aryan.pqciotframework.database.DatabaseManager;
import com.aryan.pqciotframework.gateway.GatewayServer;
import com.aryan.pqciotframework.input.DeviceSimulator;
import com.aryan.pqciotframework.logging.ImmutableLogger;
import com.aryan.pqciotframework.model.AnomalyEvent;
import com.aryan.pqciotframework.model.SecurePacket;
import com.aryan.pqciotframework.model.SensorData;
import com.aryan.pqciotframework.config.RunContext;
import com.aryan.pqciotframework.security.ClassicalSecurityService;
import com.aryan.pqciotframework.security.PQCService;
import com.aryan.pqciotframework.security.SecurityService;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ScalabilityRunner {

    public static void main(String[] args) throws Exception {
        DatabaseManager.createTablesIfNotExist();
        String runId = RunContext.resolveOrCreate("scalability");
        System.out.println("[RUN] Active run_id: " + runId);

        int[] deviceCounts = {10, 20, 30, 40, 50};
        int readingsPerDevice = 20;

        runMode("PQC", new PQCService(), deviceCounts, readingsPerDevice);
        runMode("CLASSICAL", new ClassicalSecurityService(), deviceCounts, readingsPerDevice);

        System.out.println("\nScalability experiments saved to table: scalability_results");
    }

    private static void runMode(String modeName, SecurityService securityService, int[] deviceCounts, int readingsPerDevice) throws Exception {
        PerformanceMonitor monitor = new PerformanceMonitor();
        AnomalyDetector anomalyDetector = new AnomalyDetector();

        System.out.println("\n=== Scalability Runner (" + modeName + " mode) ===");

        for (int deviceCount : deviceCounts) {
            System.out.println("\n--- Running for " + modeName + ", device count: " + deviceCount + " ---");

            GatewayServer gateway = new GatewayServer(securityService, new ImmutableLogger());

            List<SensorData> rawData = DeviceSimulator.simulateMultipleDevices(deviceCount, readingsPerDevice);
            int totalPackets = rawData.size();

            Set<String> deviceIds = new HashSet<>();
            for (SensorData data : rawData) {
                deviceIds.add(data.getDeviceId());
            }

            for (String deviceId : deviceIds) {
                gateway.registerDevice(deviceId);
                byte[] challenge = gateway.generateChallenge(deviceId);
                byte[] sig       = gateway.signChallengeAsDevice(deviceId, challenge);
                gateway.authenticateDevice(deviceId, challenge, sig);
            }

            List<SensorData> processedData = rawData;
            List<AnomalyEvent> anomalies = anomalyDetector.detectAnomalies(processedData, 3, 0.1, 2.0);
            int anomalyCount = (int) anomalies.stream().filter(AnomalyEvent::isAnomaly).count();

            long start = System.currentTimeMillis();
            int processedPackets = 0;

            for (SensorData data : processedData) {
                try {
                    String payload = data.getSensorValue() + ":" + data.getTimestamp();
                    SecurePacket packet = gateway.createSecurePacket(data.getDeviceId(), payload);
                    gateway.processPacket(packet);
                    processedPackets++;
                } catch (Exception ignored) {
                }
            }

            long elapsedMs = Math.max(1, System.currentTimeMillis() - start);

            double avgLatencyMs = (double) elapsedMs / Math.max(1, processedPackets);
            double throughputPps = processedPackets / (elapsedMs / 1000.0);
            double cpu = monitor.getCpuUsage();
            double mem = monitor.getMemoryUsage();
            double dropRate = ((double) (totalPackets - processedPackets) / totalPackets) * 100.0;

            DatabaseManager.saveScalabilityResult(
                    modeName,
                    deviceCount,
                    totalPackets,
                    processedPackets,
                    avgLatencyMs,
                    throughputPps,
                    cpu,
                    mem,
                    dropRate,
                    anomalyCount,
                    System.currentTimeMillis()
            );

            System.out.println("Packets: " + processedPackets + "/" + totalPackets);
            System.out.println("Avg Latency: " + String.format("%.3f ms", avgLatencyMs));
            System.out.println("Throughput: " + String.format("%.2f pps", throughputPps));
            System.out.println("CPU: " + String.format("%.2f%%", cpu) + ", Memory: " + String.format("%.2f MB", mem));
            System.out.println("Drop Rate: " + String.format("%.2f%%", dropRate));
        }
    }
}