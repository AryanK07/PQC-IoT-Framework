package com.aryan.pqciotframework.model;

public class SensorData {
    private int id;
    private String deviceId;
    private double sensorValue;
    private long timestamp;
    private byte[] encryptedData;
    private byte[] signature;

    public SensorData() {}

    public SensorData(String deviceId, double sensorValue, long timestamp) {
        this.deviceId = deviceId;
        this.sensorValue = sensorValue;
        this.timestamp = timestamp;
    }

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }

    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String deviceId) { this.deviceId = deviceId; }

    public double getSensorValue() { return sensorValue; }
    public void setSensorValue(double sensorValue) { this.sensorValue = sensorValue; }

    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }

    public byte[] getEncryptedData() { return encryptedData; }
    public void setEncryptedData(byte[] encryptedData) { this.encryptedData = encryptedData; }

    public byte[] getSignature() { return signature; }
    public void setSignature(byte[] signature) { this.signature = signature; }

    @Override
    public String toString() {
        return "SensorData{" +
                "id=" + id +
                ", deviceId='" + deviceId + '\'' +
                ", sensorValue=" + sensorValue +
                ", timestamp=" + timestamp +
                '}';
    }
}