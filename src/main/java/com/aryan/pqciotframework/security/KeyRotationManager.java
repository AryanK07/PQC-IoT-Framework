package com.aryan.pqciotframework.security;

import com.aryan.pqciotframework.logging.ImmutableLogger;
import com.aryan.pqciotframework.database.DatabaseManager;

import java.security.KeyPair;
import java.util.HashMap;
import java.util.Map;

public class KeyRotationManager {

    private final int maxPacketsPerKey;
    private final long maxKeyAgeMillis;
    private final Map<String, RotationState> deviceStates = new HashMap<>();
    private final ImmutableLogger logger;
    private final boolean verboseConsoleOutput;

    public KeyRotationManager(int maxPacketsPerKey, long maxKeyAgeMillis, ImmutableLogger logger) {
        this(maxPacketsPerKey, maxKeyAgeMillis, logger, true);
    }

    public KeyRotationManager(int maxPacketsPerKey, long maxKeyAgeMillis, ImmutableLogger logger, boolean verboseConsoleOutput) {
        this.maxPacketsPerKey = maxPacketsPerKey;
        this.maxKeyAgeMillis = maxKeyAgeMillis;
        this.logger = logger;
        this.verboseConsoleOutput = verboseConsoleOutput;
    }

    public static class RotationState {
        private final String deviceId;
        private CryptoKeyBundle currentKeyBundle;
        private int keyVersion;
        private int packetCount;
        private long lastRotationTime;

        public RotationState(String deviceId, CryptoKeyBundle currentKeyBundle) {
            this.deviceId = deviceId;
            this.currentKeyBundle = currentKeyBundle;
            this.keyVersion = 1;
            this.packetCount = 0;
            this.lastRotationTime = System.currentTimeMillis();
        }

        public String getDeviceId() {
            return deviceId;
        }

        public CryptoKeyBundle getCurrentKeyBundle() {
            return currentKeyBundle;
        }

        public void setCurrentKeyBundle(CryptoKeyBundle currentKeyBundle) {
            this.currentKeyBundle = currentKeyBundle;
        }

        public int getKeyVersion() {
            return keyVersion;
        }

        public void incrementKeyVersion() {
            this.keyVersion++;
        }

        public int getPacketCount() {
            return packetCount;
        }

        public void incrementPacketCount() {
            this.packetCount++;
        }

        public void resetPacketCount() {
            this.packetCount = 0;
        }

        public long getLastRotationTime() {
            return lastRotationTime;
        }

        public void setLastRotationTime(long lastRotationTime) {
            this.lastRotationTime = lastRotationTime;
        }
    }

    public void registerDevice(String deviceId, SecurityService securityService) throws Exception {
        CryptoKeyBundle initialKeyBundle = securityService.generateKeyBundle();
        RotationState state = new RotationState(deviceId, initialKeyBundle);
        deviceStates.put(deviceId, state);

        if (logger != null) {
            logger.logEvent(
                    "KEY_REGISTERED",
                    "deviceId=" + deviceId + ", keyVersion=1, mode=" + securityService.getModeName(),
                    verboseConsoleOutput
            );
        }

        if (verboseConsoleOutput) {
            System.out.println("KeyRotationManager: registered " + deviceId + " with keyVersion=1");
        }
    }

    public boolean isDeviceRegistered(String deviceId) {
        return deviceStates.containsKey(deviceId);
    }

    public RotationState getRotationState(String deviceId) {
        return deviceStates.get(deviceId);
    }

    public CryptoKeyBundle getCurrentKeyBundle(String deviceId) {
        RotationState state = deviceStates.get(deviceId);
        return state != null ? state.getCurrentKeyBundle() : null;
    }

    public int getCurrentKeyVersion(String deviceId) {
        RotationState state = deviceStates.get(deviceId);
        return state != null ? state.getKeyVersion() : -1;
    }

    public void recordPacketAndRotateIfNeeded(String deviceId, SecurityService securityService) throws Exception {
        RotationState state = deviceStates.get(deviceId);
        if (state == null) {
            throw new IllegalArgumentException("Device not registered: " + deviceId);
        }

        state.incrementPacketCount();

        if (shouldRotate(state)) {
            rotateKey(deviceId, securityService, determineRotationReason(state));
        }
    }

    public boolean validateKeyVersion(String deviceId, int receivedKeyVersion) {
        RotationState state = deviceStates.get(deviceId);
        if (state == null) {
            return false;
        }
        return state.getKeyVersion() == receivedKeyVersion;
    }

    public void rotateKey(String deviceId, SecurityService securityService, String reason) throws Exception {
        RotationState state = deviceStates.get(deviceId);
        if (state == null) {
            throw new IllegalArgumentException("Device not registered: " + deviceId);
        }

        state.setCurrentKeyBundle(securityService.generateKeyBundle());
        state.incrementKeyVersion();
        state.resetPacketCount();

        long timestamp = System.currentTimeMillis();
        state.setLastRotationTime(timestamp);

        DatabaseManager.saveKeyRotationLog(
                deviceId,
                state.getKeyVersion(),
                reason,
                securityService.getModeName(),
                timestamp
        );

        if (logger != null) {
            logger.logEvent(
                    "KEY_ROTATED",
                    "deviceId=" + deviceId +
                            ", keyVersion=" + state.getKeyVersion() +
                            ", reason=" + reason +
                    ", mode=" + securityService.getModeName(),
                verboseConsoleOutput
            );
        }

        if (verboseConsoleOutput) {
            System.out.println(
                "KeyRotationManager: rotated key for " + deviceId +
                    " to keyVersion=" + state.getKeyVersion() +
                    " (" + reason + ")"
            );
        }
    }

    private boolean shouldRotate(RotationState state) {
        boolean packetLimitReached = state.getPacketCount() >= maxPacketsPerKey;
        boolean timeLimitReached = (System.currentTimeMillis() - state.getLastRotationTime()) >= maxKeyAgeMillis;
        return packetLimitReached || timeLimitReached;
    }

    private String determineRotationReason(RotationState state) {
        boolean packetLimitReached = state.getPacketCount() >= maxPacketsPerKey;
        boolean timeLimitReached = (System.currentTimeMillis() - state.getLastRotationTime()) >= maxKeyAgeMillis;

        if (packetLimitReached && timeLimitReached) {
            return "PACKET_LIMIT_AND_TIME_LIMIT";
        }
        if (packetLimitReached) {
            return "PACKET_LIMIT";
        }
        return "TIME_LIMIT";
    }

    public void printStatus() {
        System.out.println("\n========== KEY ROTATION STATUS ==========");
        for (RotationState state : deviceStates.values()) {
            long ageMillis = System.currentTimeMillis() - state.getLastRotationTime();
            System.out.println("Device: " + state.getDeviceId());
            System.out.println("  Key Version: " + state.getKeyVersion());
            System.out.println("  Packet Count: " + state.getPacketCount() + "/" + maxPacketsPerKey);
            System.out.println("  Key Age: " + ageMillis + " ms");
        }
        System.out.println("=========================================\n");
    }

    public static void main(String[] args) throws Exception {
        ImmutableLogger logger = new ImmutableLogger();
        SecurityService pqcService = new PQCService();

        KeyRotationManager manager = new KeyRotationManager(
                3,
                10_000,
                logger
        );

        manager.registerDevice("device-1", pqcService);

        System.out.println("Initial keyVersion: " + manager.getCurrentKeyVersion("device-1"));

        manager.recordPacketAndRotateIfNeeded("device-1", pqcService);
        manager.recordPacketAndRotateIfNeeded("device-1", pqcService);
        manager.recordPacketAndRotateIfNeeded("device-1", pqcService);

        System.out.println("After packet-trigger rotation keyVersion: " + manager.getCurrentKeyVersion("device-1"));
        manager.printStatus();

        boolean valid = manager.validateKeyVersion("device-1", manager.getCurrentKeyVersion("device-1"));
        boolean invalid = manager.validateKeyVersion("device-1", 1);

        System.out.println("Current version valid: " + valid);
        System.out.println("Old version valid: " + invalid);
    }
}