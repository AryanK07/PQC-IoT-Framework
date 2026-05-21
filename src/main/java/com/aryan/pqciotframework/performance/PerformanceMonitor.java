package com.aryan.pqciotframework.performance;

import com.aryan.pqciotframework.model.PerformanceMetric;
import com.sun.management.OperatingSystemMXBean;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.util.ArrayList;
import java.util.List;

public class PerformanceMonitor {

    private OperatingSystemMXBean osBean = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
    private MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
    private List<PerformanceMetric> metrics = new ArrayList<>();

    // ===== CPU & MEMORY MONITORING =====

    public double getCpuUsage() {
        return osBean.getProcessCpuLoad() * 100;
    }

    public double getMemoryUsage() {
        long usedMemory = memoryBean.getHeapMemoryUsage().getUsed();
        return usedMemory / (1024.0 * 1024.0); // Convert to MB
    }

    // ===== METRIC RECORDING =====

    public void recordMetric(String operation, long executionTimeMs) {
        double cpuUsage = getCpuUsage();
        double memoryUsage = getMemoryUsage();
        long timestamp = System.currentTimeMillis();

        PerformanceMetric metric = new PerformanceMetric(
                operation,
                executionTimeMs,
                cpuUsage,
                memoryUsage,
                timestamp
        );

        metrics.add(metric);
        System.out.println("Recorded: " + metric);
    }

    // ===== STATISTICAL ANALYSIS =====

    public double getAverageExecutionTime(String operation) {
        return metrics.stream()
                .filter(m -> m.getOperation().equals(operation))
                .mapToLong(PerformanceMetric::getExecutionTimeMs)
                .average()
                .orElse(0.0);
    }

    public double getMaxExecutionTime(String operation) {
        return metrics.stream()
                .filter(m -> m.getOperation().equals(operation))
                .mapToLong(PerformanceMetric::getExecutionTimeMs)
                .max()
                .orElse(0);
    }

    public double getMinExecutionTime(String operation) {
        return metrics.stream()
                .filter(m -> m.getOperation().equals(operation))
                .mapToLong(PerformanceMetric::getExecutionTimeMs)
                .min()
                .orElse(0);
    }

    public double getAverageCpuUsage() {
        return metrics.stream()
                .mapToDouble(PerformanceMetric::getCpuUsagePercent)
                .average()
                .orElse(0.0);
    }

    public double getAverageMemoryUsage() {
        return metrics.stream()
                .mapToDouble(PerformanceMetric::getMemoryUsageMb)
                .average()
                .orElse(0.0);
    }

    // ===== REPORTING =====

    public void generateReport() {
        System.out.println("\n========== PERFORMANCE REPORT ==========");
        System.out.println("Total metrics recorded: " + metrics.size());

        if (!metrics.isEmpty()) {
            System.out.println("\nAverage CPU Usage: " + String.format("%.2f%%", getAverageCpuUsage()));
            System.out.println("Average Memory Usage: " + String.format("%.2f MB", getAverageMemoryUsage()));

            // Group by operation
            metrics.stream()
                    .map(PerformanceMetric::getOperation)
                    .distinct()
                    .forEach(op -> {
                        long count = metrics.stream().filter(m -> m.getOperation().equals(op)).count();
                        double avg = getAverageExecutionTime(op);
                        double min = getMinExecutionTime(op);
                        double max = getMaxExecutionTime(op);

                        System.out.println("\nOperation: " + op);
                        System.out.println("  Count: " + count);
                        System.out.println("  Avg Time: " + String.format("%.2f ms", avg));
                        System.out.println("  Min Time: " + String.format("%.2f ms", min));
                        System.out.println("  Max Time: " + String.format("%.2f ms", max));
                    });
        }

        System.out.println("\n=======================================\n");
    }

    // ===== EXPORT METRICS =====

    public List<PerformanceMetric> getMetrics() {
        return new ArrayList<>(metrics);
    }

    public void clearMetrics() {
        metrics.clear();
    }

    public static void main(String[] args) throws InterruptedException {
        PerformanceMonitor monitor = new PerformanceMonitor();

        // Simulate operations
        System.out.println("=== Simulating Operations ===");

        for (int i = 0; i < 5; i++) {
            long start = System.currentTimeMillis();
            Thread.sleep(50 + (int)(Math.random() * 50)); // Simulate work
            long elapsed = System.currentTimeMillis() - start;

            monitor.recordMetric("KeyGeneration", elapsed);
        }

        for (int i = 0; i < 5; i++) {
            long start = System.currentTimeMillis();
            Thread.sleep(30 + (int)(Math.random() * 30)); // Simulate work
            long elapsed = System.currentTimeMillis() - start;

            monitor.recordMetric("Encryption", elapsed);
        }

        // Generate report
        monitor.generateReport();
    }
}