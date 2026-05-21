package com.aryan.pqciotframework.model;

public class AnomalyEvent {
    private int id;
    private String deviceId;
    private double sensorValue;
    private double anomalyScore;
    private boolean isAnomaly;
    private String detectionMethod;
    private long timestamp;

    public AnomalyEvent() {}

    public AnomalyEvent(String deviceId, double sensorValue, double anomalyScore,
                        boolean isAnomaly, String detectionMethod, long timestamp) {
        this.deviceId = deviceId;
        this.sensorValue = sensorValue;
        this.anomalyScore = anomalyScore;
        this.isAnomaly = isAnomaly;
        this.detectionMethod = detectionMethod;
        this.timestamp = timestamp;
    }

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }

    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String deviceId) { this.deviceId = deviceId; }

    public double getSensorValue() { return sensorValue; }
    public void setSensorValue(double sensorValue) { this.sensorValue = sensorValue; }

    public double getAnomalyScore() { return anomalyScore; }
    public void setAnomalyScore(double anomalyScore) { this.anomalyScore = anomalyScore; }

    public boolean isAnomaly() { return isAnomaly; }
    public void setAnomaly(boolean anomaly) { isAnomaly = anomaly; }

    public String getDetectionMethod() { return detectionMethod; }
    public void setDetectionMethod(String detectionMethod) { this.detectionMethod = detectionMethod; }

    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }

    @Override
    public String toString() {
        return "AnomalyEvent{" +
                "id=" + id +
                ", deviceId='" + deviceId + '\'' +
                ", anomalyScore=" + anomalyScore +
                ", isAnomaly=" + isAnomaly +
                '}';
    }
}