package com.aryan.pqciotframework;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Generates a grouped mean/SD summary from the consolidated sweep CSV produced by EvaluationSweepRunner.
 *
 * Default input:  paper_final/data/eval_sweep.csv
 * Default output: paper_final/data/eval_sweep_summary.csv
 */
public class EvaluationSummaryGenerator {

    private static final class RunningStats {
        private int n;
        private double mean;
        private double m2;

        void add(double x) {
            n++;
            double delta = x - mean;
            mean += delta / n;
            double delta2 = x - mean;
            m2 += delta * delta2;
        }

        int n() {
            return n;
        }

        double mean() {
            return mean;
        }

        double sampleSd() {
            if (n <= 1) {
                return 0.0;
            }
            return Math.sqrt(m2 / (n - 1));
        }
    }

    private static final class GroupStats {
        final RunningStats throughput = new RunningStats();
        final RunningStats latency = new RunningStats();
        final RunningStats drop = new RunningStats();
        final RunningStats cpuUsage = new RunningStats();
        final RunningStats memoryUsage = new RunningStats();
        final RunningStats cpuUsageMax = new RunningStats();
        final RunningStats memoryUsageMax = new RunningStats();
        final RunningStats cpuUsageEnd = new RunningStats();
        final RunningStats memoryUsageEnd = new RunningStats();
        final RunningStats resourceSampleCount = new RunningStats();
        final RunningStats payloadBytesAvg = new RunningStats();
        final RunningStats envelopeBytesAvg = new RunningStats();
        final RunningStats signatureBytesAvg = new RunningStats();
        final RunningStats precision = new RunningStats();
        final RunningStats recall = new RunningStats();
        final RunningStats f1 = new RunningStats();
        final RunningStats attackRate = new RunningStats();

        void addRow(Map<String, Integer> idx, String[] row) {
            throughput.add(readDouble(idx, row, "throughput_pps"));
            latency.add(readDouble(idx, row, "avg_latency_ms"));
            drop.add(readDouble(idx, row, "packet_drop_rate_percent"));
            cpuUsage.add(readDouble(idx, row, "cpu_usage_percent"));
            memoryUsage.add(readDouble(idx, row, "memory_usage_mb"));

            addOptional(cpuUsageMax, readOptionalDouble(idx, row, "cpu_usage_max_percent"));
            addOptional(memoryUsageMax, readOptionalDouble(idx, row, "memory_usage_max_mb"));
            addOptional(cpuUsageEnd, readOptionalDouble(idx, row, "cpu_usage_end_percent"));
            addOptional(memoryUsageEnd, readOptionalDouble(idx, row, "memory_usage_end_mb"));
            addOptional(resourceSampleCount, readOptionalDouble(idx, row, "resource_sample_count"));

            addOptional(payloadBytesAvg, readOptionalDouble(idx, row, "payload_bytes_avg"));
            addOptional(envelopeBytesAvg, readOptionalDouble(idx, row, "envelope_bytes_avg"));
            addOptional(signatureBytesAvg, readOptionalDouble(idx, row, "signature_bytes_avg"));

            // Detection metrics may be blank; treat missing as NaN and skip.
            addOptional(precision, readOptionalDouble(idx, row, "det_precision"));
            addOptional(recall, readOptionalDouble(idx, row, "det_recall"));
            addOptional(f1, readOptionalDouble(idx, row, "det_f1"));
            addOptional(attackRate, readOptionalDouble(idx, row, "det_attack_rate"));
        }

        private static void addOptional(RunningStats stats, Double value) {
            if (value == null || value.isNaN() || value.isInfinite()) {
                return;
            }
            stats.add(value);
        }
    }

    public static void main(String[] args) throws Exception {
        String input = args != null && args.length >= 1 && args[0] != null && !args[0].isBlank()
                ? args[0]
                : Path.of("paper_final", "data", "eval_sweep.csv").toString();

        String output = args != null && args.length >= 2 && args[1] != null && !args[1].isBlank()
                ? args[1]
                : Path.of("paper_final", "data", "eval_sweep_summary.csv").toString();

        Path inputPath = Path.of(input);
        Path outputPath = Path.of(output);
        if (outputPath.getParent() != null) {
            Files.createDirectories(outputPath.getParent());
        }

        List<String> lines = Files.readAllLines(inputPath, StandardCharsets.UTF_8);
        if (lines.isEmpty()) {
            throw new IllegalStateException("Input CSV is empty: " + inputPath.toAbsolutePath());
        }

        String[] header = splitCsvLine(lines.get(0));
        Map<String, Integer> idx = indexHeader(header);

        String[] required = {
                "crypto_mode",
                "packet_interval_ms",
                "device_count",
                "throughput_pps",
                "avg_latency_ms",
                "packet_drop_rate_percent",
                "cpu_usage_percent",
                "memory_usage_mb"
        };
        for (String col : required) {
            if (!idx.containsKey(col)) {
                throw new IllegalStateException("Missing required column: " + col);
            }
        }

        Map<String, GroupStats> groups = new TreeMap<>();

        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line == null || line.isBlank()) {
                continue;
            }
            String[] row = splitCsvLine(line);

            String cryptoMode = readString(idx, row, "crypto_mode");
            String intervalMs = readString(idx, row, "packet_interval_ms");
            String deviceCount = readString(idx, row, "device_count");
            String key = cryptoMode + "|" + intervalMs + "|" + deviceCount;

            GroupStats stats = groups.computeIfAbsent(key, k -> new GroupStats());
            stats.addRow(idx, row);
        }

        List<String> out = new ArrayList<>();
        out.add(String.join(",",
                "crypto_mode",
                "packet_interval_ms",
                "device_count",
                "runs",
                "throughput_mean",
                "throughput_sd",
                "latency_mean",
                "latency_sd",
                "drop_mean",
                "drop_sd",
            "cpu_usage_mean",
            "cpu_usage_sd",
            "memory_usage_mean",
            "memory_usage_sd",
            "cpu_usage_max_mean",
            "cpu_usage_max_sd",
            "memory_usage_max_mean",
            "memory_usage_max_sd",
            "cpu_usage_end_mean",
            "cpu_usage_end_sd",
            "memory_usage_end_mean",
            "memory_usage_end_sd",
            "resource_sample_count_mean",
            "resource_sample_count_sd",
            "payload_bytes_avg_mean",
            "payload_bytes_avg_sd",
            "envelope_bytes_avg_mean",
            "envelope_bytes_avg_sd",
            "signature_bytes_avg_mean",
            "signature_bytes_avg_sd",
                "det_precision_mean",
                "det_precision_sd",
                "det_recall_mean",
                "det_recall_sd",
                "det_f1_mean",
                "det_f1_sd",
                "det_attack_rate_mean",
                "det_attack_rate_sd"
        ));

        for (Map.Entry<String, GroupStats> entry : groups.entrySet()) {
            String[] parts = entry.getKey().split("\\|", -1);
            String cryptoMode = parts[0];
            String intervalMs = parts[1];
            String deviceCount = parts[2];
            GroupStats s = entry.getValue();

            out.add(String.format(Locale.US,
                    "%s,%s,%s,%d,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f",
                    cryptoMode,
                    intervalMs,
                    deviceCount,
                    s.throughput.n(),
                    s.throughput.mean(),
                    s.throughput.sampleSd(),
                    s.latency.mean(),
                    s.latency.sampleSd(),
                    s.drop.mean(),
                    s.drop.sampleSd(),
                    s.cpuUsage.mean(),
                    s.cpuUsage.sampleSd(),
                    s.memoryUsage.mean(),
                    s.memoryUsage.sampleSd(),
                    s.cpuUsageMax.mean(),
                    s.cpuUsageMax.sampleSd(),
                    s.memoryUsageMax.mean(),
                    s.memoryUsageMax.sampleSd(),
                    s.cpuUsageEnd.mean(),
                    s.cpuUsageEnd.sampleSd(),
                    s.memoryUsageEnd.mean(),
                    s.memoryUsageEnd.sampleSd(),
                    s.resourceSampleCount.mean(),
                    s.resourceSampleCount.sampleSd(),
                    s.payloadBytesAvg.mean(),
                    s.payloadBytesAvg.sampleSd(),
                    s.envelopeBytesAvg.mean(),
                    s.envelopeBytesAvg.sampleSd(),
                    s.signatureBytesAvg.mean(),
                    s.signatureBytesAvg.sampleSd(),
                    s.precision.mean(),
                    s.precision.sampleSd(),
                    s.recall.mean(),
                    s.recall.sampleSd(),
                    s.f1.mean(),
                    s.f1.sampleSd(),
                    s.attackRate.mean(),
                    s.attackRate.sampleSd()
            ));
        }

        Files.writeString(outputPath, String.join(System.lineSeparator(), out) + System.lineSeparator(), StandardCharsets.UTF_8);
        System.out.println("Wrote summary CSV: " + outputPath.toAbsolutePath());
        System.out.println("Groups: " + groups.size());
    }

    private static String[] splitCsvLine(String line) {
        return line.split(",", -1);
    }

    private static Map<String, Integer> indexHeader(String[] header) {
        Map<String, Integer> idx = new HashMap<>();
        for (int i = 0; i < header.length; i++) {
            idx.put(header[i].trim(), i);
        }
        return idx;
    }

    private static String readString(Map<String, Integer> idx, String[] row, String col) {
        Integer i = idx.get(col);
        if (i == null || i < 0 || i >= row.length) {
            return "";
        }
        return row[i].trim();
    }

    private static double readDouble(Map<String, Integer> idx, String[] row, String col) {
        String raw = readString(idx, row, col);
        if (raw.isBlank()) {
            return 0.0;
        }
        return Double.parseDouble(raw);
    }

    private static Double readOptionalDouble(Map<String, Integer> idx, String[] row, String col) {
        if (!idx.containsKey(col)) {
            return null;
        }
        String raw = readString(idx, row, col);
        if (raw.isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
