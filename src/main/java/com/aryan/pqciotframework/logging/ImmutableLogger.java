package com.aryan.pqciotframework.logging;

import java.sql.Connection;
import java.sql.PreparedStatement;
import com.aryan.pqciotframework.database.DatabaseManager;

import com.aryan.pqciotframework.model.SystemLog;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

public class ImmutableLogger {

    private List<SystemLog> logs = new ArrayList<>();
    private String lastHash = "0";

    // ===== SHA-256 HASHING =====

    public String calculateHash(String input) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hashBytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
        return bytesToHex(hashBytes);
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder hexString = new StringBuilder();
        for (byte b : bytes) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) hexString.append('0');
            hexString.append(hex);
        }
        return hexString.toString();
    }

    // ===== HASH CHAINING =====

    public SystemLog logEvent(String eventType, String eventData) throws Exception {
        return logEvent(eventType, eventData, true);
    }

    public SystemLog logEvent(String eventType, String eventData, boolean printToConsole) throws Exception {
        long timestamp = System.currentTimeMillis();

        // Create log content
        String logContent = eventType + "|" + eventData + "|" + lastHash + "|" + timestamp;
        String currentHash = calculateHash(logContent);

        // Create log entry
        SystemLog log = new SystemLog(
                currentHash,
                lastHash,
                eventType,
                eventData,
                timestamp
        );

        // === PERSIST TO DATABASE ===
        try (Connection connection = DatabaseManager.getConnection()) {
            String insertSQL = "INSERT INTO system_logs (log_hash, previous_hash, run_id, event_type, event_data, timestamp) VALUES (?, ?, ?, ?, ?, ?)";
            try (PreparedStatement pstmt = connection.prepareStatement(insertSQL)) {
                pstmt.setString(1, currentHash);
                pstmt.setString(2, lastHash);
                pstmt.setString(3, DatabaseManager.getCurrentRunId());
                pstmt.setString(4, eventType);
                pstmt.setString(5, eventData);
                pstmt.setLong(6, timestamp);
                pstmt.executeUpdate();
            }
            // Commit transaction (MySQL autocommit may be enabled)
            if (!connection.getAutoCommit()) {
                connection.commit();
            }
        } catch (Exception e) {
            System.err.println("Failed to persist log to database: " + e.getMessage());
            throw e;
        }

        logs.add(log);
        lastHash = currentHash;

        if (printToConsole) {
            System.out.println("Logged: " + eventType + " -> " + currentHash.substring(0, 16) + "...");
        }
        return log;
    }

    // ===== VERIFICATION =====

    public boolean verifyIntegrity() throws Exception {
        if (logs.isEmpty()) return true;

        String computedHash = "0";
        for (SystemLog log : logs) {
            String logContent = log.getEventType() + "|" + log.getEventData() + "|" + computedHash + "|" + log.getTimestamp();
            computedHash = calculateHash(logContent);

            if (!computedHash.equals(log.getLogHash())) {
                System.out.println("TAMPERING DETECTED at: " + log.getEventType());
                return false;
            }
        }

        return true;
    }

    // ===== RETRIEVAL & EXPORT =====

    public List<SystemLog> getAllLogs() {
        return new ArrayList<>(logs);
    }

    public void printAllLogs() {
        System.out.println("\n========== IMMUTABLE LOG CHAIN ==========");
        for (int i = 0; i < logs.size(); i++) {
            SystemLog log = logs.get(i);
            String hashPreview = log.getLogHash().length() > 16 ? log.getLogHash().substring(0, 16) + "..." : log.getLogHash();
            String prevHashPreview = log.getPreviousHash() == null || log.getPreviousHash().equals("0") ?
                    "NONE" : (log.getPreviousHash().length() > 16 ? log.getPreviousHash().substring(0, 16) + "..." : log.getPreviousHash());

            System.out.println((i + 1) + ". Type: " + log.getEventType());
            System.out.println("   Data: " + log.getEventData());
            System.out.println("   Hash: " + hashPreview);
            System.out.println("   Previous: " + prevHashPreview);
        }
        System.out.println("=========================================\n");
    }

    public void clearLogs() {
        logs.clear();
        lastHash = "0";
    }

    public static void main(String[] args) throws Exception {
        ImmutableLogger logger = new ImmutableLogger();

        System.out.println("=== Logging Events ===");
        logger.logEvent("DEVICE_CONNECTED", "device-1 connected from 192.168.1.100");
        logger.logEvent("ANOMALY_DETECTED", "Anomaly score: 0.95 for device-1");
        logger.logEvent("DEVICE_DISCONNECTED", "device-1 disconnected");

        // Print logs
        logger.printAllLogs();

        // Verify integrity
        System.out.println("=== Integrity Verification ===");
        boolean isValid = logger.verifyIntegrity();
        System.out.println("Chain is valid: " + isValid);

        // Try tampering (simulate)
        System.out.println("\n=== Simulating Tampering ===");
        logger.logs.get(1).setEventData("MODIFIED DATA");
        isValid = logger.verifyIntegrity();
        System.out.println("Chain is valid after tampering: " + isValid);
    }
}