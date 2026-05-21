package com.aryan.pqciotframework;

import com.aryan.pqciotframework.config.RunContext;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class EvaluationSweepRunner {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    public static void main(String[] args) throws Exception {
        System.out.println("\n" + "=".repeat(70));
        System.out.println("EVALUATION SWEEP RUNNER");
        System.out.println("=".repeat(70));

        long durationMs = readLongProp("pqciot.eval.durationMs", 60_000L);
        int deviceCount = readIntProp("pqciot.eval.deviceCount", 50);
        int repeats = readIntProp("pqciot.eval.repeats", 1);

        List<Integer> deviceCounts = parseIntList(System.getProperty("pqciot.eval.deviceCountList", String.valueOf(deviceCount)));
        if (deviceCounts.isEmpty()) {
            deviceCounts = List.of(deviceCount);
        }

        List<Long> intervalMsList = parseLongList(System.getProperty("pqciot.eval.intervalMsList", "50"));
        List<String> cryptoModes = parseStringList(System.getProperty("pqciot.eval.cryptoModes", "HYBRID,PQC_ONLY,CLASSICAL_ONLY"));

        String defaultOut = Path.of("paper_final", "data", "eval_sweep_" + LocalDateTime.now().format(TS) + ".csv").toString();
        String outputPathStr = System.getProperty("pqciot.eval.output", defaultOut);
        Path outputPath = Path.of(outputPathStr);
        if (outputPath.getParent() != null) {
            Files.createDirectories(outputPath.getParent());
        }

        System.out.println("\nConfig:");
        System.out.println("  durationMs=" + durationMs);
        System.out.println("  deviceCounts=" + deviceCounts);
        System.out.println("  repeats=" + repeats);
        System.out.println("  intervalsMs=" + intervalMsList);
        System.out.println("  cryptoModes=" + cryptoModes);
        System.out.println("  output=" + outputPath.toAbsolutePath());

        List<String> lines = new ArrayList<>();
        lines.add(String.join(",",
                "run_label",
                "run_id",
                "mode_name",
                "crypto_mode",
                "device_count",
                "packet_interval_ms",
                "duration_seconds",
                "total_packets",
                "processed_packets",
                "rejected_packets",
                "packet_drop_rate_percent",
                "avg_latency_ms",
                "throughput_pps",
                "anomaly_count",
                "cpu_usage_percent",
                "memory_usage_mb",
            "cpu_usage_max_percent",
            "memory_usage_max_mb",
            "cpu_usage_end_percent",
            "memory_usage_end_mb",
            "resource_sample_count",
            "payload_bytes_avg",
            "envelope_bytes_avg",
            "signature_bytes_avg",
                "det_precision",
                "det_recall",
                "det_f1",
                "det_attack_rate"
        ));

        int runNumber = 0;
        for (String cryptoMode : cryptoModes) {
            for (long intervalMs : intervalMsList) {
                for (int deviceCountValue : deviceCounts) {
                    for (int rep = 1; rep <= repeats; rep++) {
                        runNumber++;
                        String runLabel = cryptoMode + "_i" + intervalMs + "_n" + deviceCountValue + "_r" + rep;

                    System.out.println("\n" + "-".repeat(70));
                    System.out.println("[SWEEP] Run " + runNumber + ": " + runLabel);
                    System.out.println("-".repeat(70));

                    // Configure the underlying integration runner.
                    System.setProperty("pqciot.finalTest.durationMs", String.valueOf(durationMs));
                    System.setProperty("pqciot.finalTest.deviceCount", String.valueOf(deviceCountValue));
                    System.setProperty("pqciot.finalTest.intervalMs", String.valueOf(intervalMs));
                    System.setProperty("pqciot.finalTest.cryptoMode", cryptoMode);

                    // Force a new run_id for each sweep run.
                    RunContext.setCurrentRunId(RunContext.generateRunId("eval"));

                    Map<String, Object> results = FinalIntegrationTest.executeIntegration();
                    if (results == null || results.isEmpty()) {
                        System.out.println("[SWEEP] WARNING: run returned no results; skipping row");
                        continue;
                    }

                    String runId = String.valueOf(results.getOrDefault("runId", RunContext.getCurrentRunId()));
                    String resolvedCryptoMode = String.valueOf(results.getOrDefault("cryptoMode", cryptoMode));
                    long resolvedIntervalMs = toLong(results.getOrDefault("packetIntervalMs", intervalMs));
                    int resolvedDeviceCount = toInt(results.getOrDefault("deviceCount", deviceCountValue));
                    String modeName = toModeName(resolvedCryptoMode);

                    long elapsedTimeMs = toLong(results.getOrDefault("elapsedTimeMs", 0L));
                    double durationSeconds = elapsedTimeMs / 1000.0;

                    int totalPackets = toInt(results.getOrDefault("totalPackets", 0));
                    int processedPackets = toInt(results.getOrDefault("processedPackets", 0));
                    int rejectedPackets = toInt(results.getOrDefault("rejectedPackets", 0));
                    int anomalyCount = toInt(results.getOrDefault("anomalyCount", 0));

                    double packetDropRate = toDouble(results.getOrDefault("packetDropRate", 0.0));
                    double avgLatencyMs = toDouble(results.getOrDefault("avgLatencyMs", 0.0));
                    double throughputPps = toDouble(results.getOrDefault("throughputPps", 0.0));
                    double cpuUsagePercent = toDouble(results.getOrDefault("cpuUsagePercent", 0.0));
                    double memoryUsageMb = toDouble(results.getOrDefault("memoryUsageMb", 0.0));

                    double cpuUsageMaxPercent = toDouble(results.getOrDefault("cpuUsageMaxPercent", 0.0));
                    double memoryUsageMaxMb = toDouble(results.getOrDefault("memoryUsageMaxMb", 0.0));
                    double cpuUsageEndPercent = toDouble(results.getOrDefault("cpuUsageEndPercent", 0.0));
                    double memoryUsageEndMb = toDouble(results.getOrDefault("memoryUsageEndMb", 0.0));
                    int resourceSampleCount = toInt(results.getOrDefault("resourceSampleCount", 0));

                    double payloadBytesAvg = toDouble(results.getOrDefault("payloadBytesAvg", 0.0));
                    double envelopeBytesAvg = toDouble(results.getOrDefault("envelopeBytesAvg", 0.0));
                    double signatureBytesAvg = toDouble(results.getOrDefault("signatureBytesAvg", 0.0));

                    String detPrecision = formatOptional(results, "det_precision");
                    String detRecall = formatOptional(results, "det_recall");
                    String detF1 = formatOptional(results, "det_f1");
                    String detAttackRate = formatOptional(results, "det_attackRate");

                        String row = String.format(Locale.US,
                            "%s,%s,%s,%s,%d,%d,%.2f,%d,%d,%d,%.3f,%.3f,%.3f,%d,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%d,%.3f,%.3f,%.3f,%s,%s,%s,%s",
                            runLabel,
                            runId,
                            modeName,
                            resolvedCryptoMode,
                            resolvedDeviceCount,
                            resolvedIntervalMs,
                            durationSeconds,
                            totalPackets,
                            processedPackets,
                            rejectedPackets,
                            packetDropRate,
                            avgLatencyMs,
                            throughputPps,
                            anomalyCount,
                            cpuUsagePercent,
                            memoryUsageMb,
                            cpuUsageMaxPercent,
                            memoryUsageMaxMb,
                            cpuUsageEndPercent,
                            memoryUsageEndMb,
                            resourceSampleCount,
                            payloadBytesAvg,
                            envelopeBytesAvg,
                            signatureBytesAvg,
                            detPrecision,
                            detRecall,
                            detF1,
                            detAttackRate
                        );

                        lines.add(row);
                    }
                }
            }
        }

        Files.writeString(outputPath, String.join(System.lineSeparator(), lines) + System.lineSeparator(), StandardCharsets.UTF_8);
        System.out.println("\n[SWEEP] Wrote consolidated CSV: " + outputPath.toAbsolutePath());
        System.out.println("[SWEEP] Rows (including header): " + lines.size());
    }

    private static String toModeName(String cryptoMode) {
        if (cryptoMode == null) {
            return "PQC+CLASSICAL";
        }
        String normalized = cryptoMode.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "PQC_ONLY", "PQC" -> "PQC_ONLY";
            case "CLASSICAL_ONLY", "CLASSICAL" -> "CLASSICAL_ONLY";
            case "HYBRID", "MIXED", "PQC+CLASSICAL" -> "PQC+CLASSICAL";
            default -> "PQC+CLASSICAL";
        };
    }

    private static String formatOptional(Map<String, Object> results, String key) {
        if (results == null || !results.containsKey(key)) {
            return "";
        }
        Object value = results.get(key);
        if (value == null) {
            return "";
        }
        try {
            return String.format(Locale.US, "%.3f", toDouble(value));
        } catch (Exception ignored) {
            return "";
        }
    }

    private static long readLongProp(String key, long defaultValue) {
        String raw = System.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private static int readIntProp(String key, int defaultValue) {
        String raw = System.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private static List<Long> parseLongList(String csv) {
        List<Long> values = new ArrayList<>();
        if (csv == null || csv.isBlank()) {
            return values;
        }
        for (String part : csv.split(",")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                values.add(Long.parseLong(trimmed));
            } catch (NumberFormatException ignored) {
                // ignore bad entries
            }
        }
        return values;
    }

    private static List<String> parseStringList(String csv) {
        List<String> values = new ArrayList<>();
        if (csv == null || csv.isBlank()) {
            return values;
        }
        for (String part : csv.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                values.add(trimmed);
            }
        }
        return values;
    }

    private static List<Integer> parseIntList(String csv) {
        List<Integer> values = new ArrayList<>();
        if (csv == null || csv.isBlank()) {
            return values;
        }
        for (String part : csv.split(",")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                values.add(Integer.parseInt(trimmed));
            } catch (NumberFormatException ignored) {
                // ignore bad entries
            }
        }
        return values;
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
}
