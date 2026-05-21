package com.aryan.pqciotframework.model;

public class PerformanceMetric {
    private int id;
    private String operation;
    private long executionTimeMs;
    private double cpuUsagePercent;
    private double memoryUsageMb;
    private long timestamp;

    public PerformanceMetric() {}

    public PerformanceMetric(String operation, long executionTimeMs,
                             double cpuUsagePercent, double memoryUsageMb, long timestamp) {
        this.operation = operation;
        this.executionTimeMs = executionTimeMs;
        this.cpuUsagePercent = cpuUsagePercent;
        this.memoryUsageMb = memoryUsageMb;
        this.timestamp = timestamp;
    }

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }

    public String getOperation() { return operation; }
    public void setOperation(String operation) { this.operation = operation; }

    public long getExecutionTimeMs() { return executionTimeMs; }
    public void setExecutionTimeMs(long executionTimeMs) { this.executionTimeMs = executionTimeMs; }

    public double getCpuUsagePercent() { return cpuUsagePercent; }
    public void setCpuUsagePercent(double cpuUsagePercent) { this.cpuUsagePercent = cpuUsagePercent; }

    public double getMemoryUsageMb() { return memoryUsageMb; }
    public void setMemoryUsageMb(double memoryUsageMb) { this.memoryUsageMb = memoryUsageMb; }

    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }

    @Override
    public String toString() {
        return "PerformanceMetric{" +
                "operation='" + operation + '\'' +
                ", executionTimeMs=" + executionTimeMs +
                '}';
    }
}