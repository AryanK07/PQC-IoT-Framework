package com.aryan.pqciotframework.model;

public class SystemLog {
    private int id;
    private String logHash;
    private String previousHash;
    private String eventType;
    private String eventData;
    private long timestamp;

    public SystemLog() {}

    public SystemLog(String logHash, String previousHash, String eventType, String eventData, long timestamp) {
        this.logHash = logHash;
        this.previousHash = previousHash;
        this.eventType = eventType;
        this.eventData = eventData;
        this.timestamp = timestamp;
    }

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }

    public String getLogHash() { return logHash; }
    public void setLogHash(String logHash) { this.logHash = logHash; }

    public String getPreviousHash() { return previousHash; }
    public void setPreviousHash(String previousHash) { this.previousHash = previousHash; }

    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }

    public String getEventData() { return eventData; }
    public void setEventData(String eventData) { this.eventData = eventData; }

    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }

    @Override
    public String toString() {
        return "SystemLog{" +
                "eventType='" + eventType + '\'' +
                ", timestamp=" + timestamp +
                '}';
    }
}