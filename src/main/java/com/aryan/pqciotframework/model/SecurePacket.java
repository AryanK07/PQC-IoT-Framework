package com.aryan.pqciotframework.model;

import java.util.Arrays;

public class SecurePacket {

    private String deviceId;
    private byte[] encryptedData;
    private byte[] signature;
    private long timestamp;
    private int keyVersion;

    public SecurePacket(String deviceId, byte[] encryptedData, byte[] signature, long timestamp, int keyVersion) {
        this.deviceId = deviceId;
        this.encryptedData = encryptedData;
        this.signature = signature;
        this.timestamp = timestamp;
        this.keyVersion = keyVersion;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public byte[] getEncryptedData() {
        return encryptedData;
    }

    public byte[] getSignature() {
        return signature;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public int getKeyVersion() {
        return keyVersion;
    }

    @Override
    public String toString() {
        return "SecurePacket{" +
                "deviceId='" + deviceId + '\'' +
                ", encryptedDataLength=" + (encryptedData == null ? 0 : encryptedData.length) +
                ", signatureLength=" + (signature == null ? 0 : signature.length) +
                ", timestamp=" + timestamp +
                ", keyVersion=" + keyVersion +
                '}';
    }
}