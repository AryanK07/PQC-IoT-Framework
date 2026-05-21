package com.aryan.pqciotframework.gateway;

import com.aryan.pqciotframework.logging.ImmutableLogger;
import com.aryan.pqciotframework.model.SecurePacket;
import com.aryan.pqciotframework.model.SensorData;
import com.aryan.pqciotframework.security.CryptoKeyBundle;
import com.aryan.pqciotframework.security.KeyRotationManager;
import com.aryan.pqciotframework.security.PQCService;
import com.aryan.pqciotframework.security.SecurityService;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

public class GatewayServer {

    // ===== AUTH POLICY CONSTANTS =====
    private static final long CHALLENGE_TTL_MS      = 5  * 60 * 1000L;  // 5 minutes
    private static final long SESSION_TTL_MS        = 60 * 60 * 1000L;  // 1 hour
    private static final int  MAX_FAILED_ATTEMPTS   = 5;
    private static final long LOCKOUT_DURATION_MS   = 5  * 60 * 1000L;  // 5 minutes
    /** Packets older or newer than this window are rejected (anti-replay). */
    private static final long PACKET_TIMESTAMP_TOLERANCE_MS = 30_000L;  // 30 seconds

    private final SecurityService securityService;
    private final ImmutableLogger logger;
    private final KeyRotationManager keyRotationManager;
    private final boolean verboseConsoleOutput;
    private final Map<String, DeviceContext> registeredDevices = new HashMap<>();
    private final SecureRandom secureRandom = new SecureRandom();

    // Device context holding keys and metadata
    public static class DeviceContext {
        public String deviceId;
        public long registeredAt;

        // Session state
        public boolean authenticated;
        public byte[] sessionToken;
        public long sessionExpiresAt;

        // Pending challenge (server-issued nonce for challenge-response auth)
        public byte[] pendingChallenge;
        public long challengeExpiresAt;

        // Brute-force / lockout tracking
        public int failedAttempts;
        public long lockedUntil;

        public DeviceContext(String deviceId) {
            this.deviceId = deviceId;
            this.registeredAt = System.currentTimeMillis();
            this.authenticated = false;
            this.failedAttempts = 0;
            this.lockedUntil = 0L;
        }
    }

    public GatewayServer() {
        this(new PQCService(), new ImmutableLogger(), true, 100);
    }

    public GatewayServer(SecurityService securityService, ImmutableLogger logger) {
        this(securityService, logger, true, 100);
    }

    public GatewayServer(SecurityService securityService, ImmutableLogger logger, boolean verboseConsoleOutput) {
        this(securityService, logger, verboseConsoleOutput, 100);
    }

    public GatewayServer(SecurityService securityService, ImmutableLogger logger, boolean verboseConsoleOutput, int maxPacketsPerKey) {
        this.securityService = securityService;
        this.logger = logger;
        this.verboseConsoleOutput = verboseConsoleOutput;
        this.keyRotationManager = new KeyRotationManager(maxPacketsPerKey, 10 * 60 * 1000L, logger, verboseConsoleOutput);
    }

    // ===== DEVICE REGISTRATION =====

    public boolean registerDevice(String deviceId) throws Exception {
        if (registeredDevices.containsKey(deviceId)) {
            System.out.println("Device already registered: " + deviceId);
            return false;
        }

        DeviceContext context = new DeviceContext(deviceId);
        registeredDevices.put(deviceId, context);
        keyRotationManager.registerDevice(deviceId, securityService);

        logger.logEvent("DEVICE_REGISTERED", "Device " + deviceId + " registered", verboseConsoleOutput);
        if (verboseConsoleOutput) {
            System.out.println("Device registered: " + deviceId);
        }
        return true;
    }

    // ===== AUTHENTICATION =====

    /**
     * Issues a cryptographically random 32-byte challenge nonce to the device.
     * The device must sign this nonce with its Dilithium private key and pass
     * the result to {@link #authenticateDevice(String, byte[], byte[])}.
     * The challenge expires after {@value #CHALLENGE_TTL_MS} ms.
     */
    public byte[] generateChallenge(String deviceId) throws Exception {
        DeviceContext context = registeredDevices.get(deviceId);
        if (context == null) {
            throw new IllegalArgumentException("Device not registered: " + deviceId);
        }
        byte[] nonce = new byte[32];
        secureRandom.nextBytes(nonce);
        context.pendingChallenge   = nonce;
        context.challengeExpiresAt = System.currentTimeMillis() + CHALLENGE_TTL_MS;
        logger.logEvent("CHALLENGE_ISSUED", "Challenge issued to " + deviceId, verboseConsoleOutput);
        return Arrays.copyOf(nonce, nonce.length);   // return a defensive copy
    }

    /**
     * Verifies the device's signature over the previously issued challenge nonce.
     * Enforces: lockout after repeated failures, challenge TTL, and signature validity.
     * On success a session token is issued; the session expires after {@value #SESSION_TTL_MS} ms.
     */
    public boolean authenticateDevice(String deviceId, byte[] challenge, byte[] signature) throws Exception {
        DeviceContext context = registeredDevices.get(deviceId);
        if (context == null) {
            logger.logEvent("AUTH_FAILED", "Device not registered: " + deviceId, verboseConsoleOutput);
            return false;
        }

        // --- Rate-limiting / lockout ---
        long now = System.currentTimeMillis();
        if (context.lockedUntil > now) {
            long remainingSec = (context.lockedUntil - now) / 1000;
                logger.logEvent("AUTH_BLOCKED",
                    "Device " + deviceId + " locked out for " + remainingSec + "s more");
                if (verboseConsoleOutput) {
                System.out.println("Authentication blocked (locked): " + deviceId
                    + " — try again in " + remainingSec + "s");
                }
            return false;
        }

        // --- Challenge nonce validation ---
        if (context.pendingChallenge == null) {
            logger.logEvent("AUTH_FAILED", "No challenge pending for " + deviceId, verboseConsoleOutput);
            return failAuth(context, deviceId, "No challenge was issued to this device");
        }
        if (now > context.challengeExpiresAt) {
            context.pendingChallenge = null;
            logger.logEvent("AUTH_FAILED", "Challenge expired for " + deviceId, verboseConsoleOutput);
            return failAuth(context, deviceId, "Challenge has expired — request a new one");
        }
        if (!Arrays.equals(context.pendingChallenge, challenge)) {
            context.pendingChallenge = null;
            return failAuth(context, deviceId, "Challenge nonce mismatch");
        }

        // Challenge consumed — clear it regardless of signature outcome
        context.pendingChallenge = null;

        // --- Signature verification ---
        CryptoKeyBundle keyBundle = keyRotationManager.getCurrentKeyBundle(deviceId);
        if (keyBundle == null) {
            logger.logEvent("AUTH_FAILED", "No key bundle found for: " + deviceId, verboseConsoleOutput);
            return false;
        }

        boolean verified = securityService.verifySignature(
                keyBundle.getSignatureKeyPair().getPublic(),
                challenge,
                signature
        );

        if (verified) {
            // Issue session token
            byte[] token = new byte[32];
            secureRandom.nextBytes(token);
            context.sessionToken      = token;
            context.sessionExpiresAt  = now + SESSION_TTL_MS;
            context.authenticated     = true;
            context.failedAttempts    = 0;
            context.lockedUntil       = 0L;
            logger.logEvent("AUTH_SUCCESS", "Device " + deviceId + " authenticated; session valid for 1h", verboseConsoleOutput);
            if (verboseConsoleOutput) {
                System.out.println("Device authenticated: " + deviceId);
            }
        } else {
            failAuth(context, deviceId, "Signature verification failed");
        }

        return verified;
    }

    /** Increments failed-attempt counter, triggers lockout if threshold reached. */
    private boolean failAuth(DeviceContext context, String deviceId, String reason) throws Exception {
        context.failedAttempts++;
        if (context.failedAttempts >= MAX_FAILED_ATTEMPTS) {
            context.lockedUntil = System.currentTimeMillis() + LOCKOUT_DURATION_MS;
            logger.logEvent("AUTH_LOCKOUT",
                    "Device " + deviceId + " locked after " + context.failedAttempts + " failures",
                    verboseConsoleOutput);
                if (verboseConsoleOutput) {
                System.out.println("Auth lockout triggered for " + deviceId
                    + " after " + context.failedAttempts + " failed attempts");
                }
        } else {
            logger.logEvent("AUTH_FAILED", deviceId + " — " + reason
                    + " (" + context.failedAttempts + "/" + MAX_FAILED_ATTEMPTS + ")",
                    verboseConsoleOutput);
                if (verboseConsoleOutput) {
                System.out.println("Authentication failed [" + context.failedAttempts + "/"
                    + MAX_FAILED_ATTEMPTS + "]: " + deviceId + " — " + reason);
                }
        }
        return false;
    }

    /**
     * Returns true if the device has an active, non-expired session.
     */
    public boolean isAuthenticated(String deviceId) {
        DeviceContext context = registeredDevices.get(deviceId);
        if (context == null || !context.authenticated) return false;
        if (System.currentTimeMillis() > context.sessionExpiresAt) {
            context.authenticated = false;   // expire the session
            try { logger.logEvent("SESSION_EXPIRED", "Session expired for " + deviceId, verboseConsoleOutput); }
            catch (Exception ignored) {}
            return false;
        }
        return true;
    }

    /**
     * Simulation-only helper: signs a challenge on behalf of the device using
     * the device's private Dilithium key stored in the key rotation manager.
     * In a real deployment the device firmware would hold its own private key.
     */
    public byte[] signChallengeAsDevice(String deviceId, byte[] challenge) throws Exception {
        CryptoKeyBundle keyBundle = keyRotationManager.getCurrentKeyBundle(deviceId);
        if (keyBundle == null) {
            throw new Exception("No key bundle for device: " + deviceId);
        }
        return securityService.signData(keyBundle.getSignatureKeyPair().getPrivate(), challenge);
    }

    public SecurePacket createSecurePacket(String deviceId, String payload) throws Exception {
        if (!registeredDevices.containsKey(deviceId)) {
            throw new Exception("Device not registered: " + deviceId);
        }

        CryptoKeyBundle keyBundle = keyRotationManager.getCurrentKeyBundle(deviceId);
        if (keyBundle == null) {
            throw new Exception("No active keys for device: " + deviceId);
        }

        long timestamp = System.currentTimeMillis();
        int keyVersion = keyRotationManager.getCurrentKeyVersion(deviceId);

        byte[] plaintext = payload.getBytes(StandardCharsets.UTF_8);
        byte[] encryptedData = securityService.encryptData(
                keyBundle.getEncryptionKeyPair().getPublic(),
                plaintext
        );
        byte[] signature = securityService.signData(
                keyBundle.getSignatureKeyPair().getPrivate(),
                plaintext
        );

        return new SecurePacket(deviceId, encryptedData, signature, timestamp, keyVersion);
    }

    // ===== PACKET PROCESSING =====

    public SensorData processPacket(SecurePacket packet) throws Exception {
        String deviceId = packet.getDeviceId();
        DeviceContext context = registeredDevices.get(deviceId);

        if (context == null) {
            logger.logEvent("PACKET_REJECTED", "Device not registered: " + deviceId, verboseConsoleOutput);
            throw new Exception("Device not registered: " + deviceId);
        }

        if (!isAuthenticated(deviceId)) {
            logger.logEvent("PACKET_REJECTED", "Device not authenticated (or session expired): " + deviceId, verboseConsoleOutput);
            throw new Exception("Device not authenticated: " + deviceId);
        }

        // Anti-replay: reject packets with a timestamp outside the ±30s tolerance window
        long ageDelta = Math.abs(System.currentTimeMillis() - packet.getTimestamp());
        if (ageDelta > PACKET_TIMESTAMP_TOLERANCE_MS) {
            logger.logEvent("PACKET_REJECTED",
                    "Stale/replayed packet from " + deviceId + " — delta " + ageDelta + "ms",
                    verboseConsoleOutput);
            throw new Exception("Packet timestamp out of tolerance for " + deviceId
                    + " (delta " + ageDelta + "ms, max " + PACKET_TIMESTAMP_TOLERANCE_MS + "ms)");
        }

        if (!keyRotationManager.validateKeyVersion(deviceId, packet.getKeyVersion())) {
            logger.logEvent("PACKET_REJECTED", "Invalid keyVersion for " + deviceId + ": " + packet.getKeyVersion(), verboseConsoleOutput);
            throw new Exception("Invalid keyVersion for " + deviceId);
        }

        CryptoKeyBundle keyBundle = keyRotationManager.getCurrentKeyBundle(deviceId);
        if (keyBundle == null) {
            throw new Exception("No active key bundle for device: " + deviceId);
        }

        byte[] decryptedData = securityService.decryptData(
                keyBundle.getEncryptionKeyPair().getPrivate(),
                packet.getEncryptedData()
        );

        boolean signatureValid = securityService.verifySignature(
                keyBundle.getSignatureKeyPair().getPublic(),
                decryptedData,
                packet.getSignature()
        );

        if (!signatureValid) {
            logger.logEvent("PACKET_REJECTED", "Invalid signature for " + deviceId, verboseConsoleOutput);
            throw new Exception("Invalid signature for " + deviceId);
        }

        String decrypted = new String(decryptedData, StandardCharsets.UTF_8);
        String[] parts = decrypted.split(":");
        double sensorValue = Double.parseDouble(parts[0]);
        long timestamp = Long.parseLong(parts[1]);

        SensorData data = new SensorData(deviceId, sensorValue, timestamp);
        data.setEncryptedData(packet.getEncryptedData());
        data.setSignature(packet.getSignature());

        keyRotationManager.recordPacketAndRotateIfNeeded(deviceId, securityService);

        logger.logEvent("PACKET_PROCESSED", "Data from " + deviceId + ": " + sensorValue, verboseConsoleOutput);
        if (verboseConsoleOutput) {
            System.out.println("Processed packet from " + deviceId + ": " + sensorValue);
        }

        return data;
    }

    // ===== DATA FORWARDING =====

    public java.util.List<SensorData> processBatch(java.util.List<SecurePacket> packets) throws Exception {
        java.util.List<SensorData> processedData = new java.util.ArrayList<>();

        for (SecurePacket packet : packets) {
            try {
                SensorData data = processPacket(packet);
                processedData.add(data);
            } catch (Exception e) {
                System.out.println("Skipping invalid packet: " + e.getMessage());
            }
        }

        return processedData;
    }

    // ===== GATEWAY STATUS =====

    public void printStatus() {
        System.out.println("\n========== GATEWAY STATUS ==========");
        System.out.println("Mode: " + securityService.getModeName());
        System.out.println("Registered Devices: " + registeredDevices.size());
        long now = System.currentTimeMillis();
        for (DeviceContext ctx : registeredDevices.values()) {
            boolean sessionActive = ctx.authenticated && now <= ctx.sessionExpiresAt;
            long sessionRemainingMin = sessionActive ? (ctx.sessionExpiresAt - now) / 60000 : 0;
            System.out.println("  - " + ctx.deviceId
                    + " | Authenticated: " + sessionActive
                    + " | Session remaining: " + sessionRemainingMin + "m"
                    + " | FailedAttempts: " + ctx.failedAttempts
                    + " | KeyVersion: " + keyRotationManager.getCurrentKeyVersion(ctx.deviceId));
        }
        System.out.println("Events Logged: " + logger.getAllLogs().size());
        System.out.println("====================================\n");
    }

    public List<SensorData> getLatestData(String deviceId) {
        return new ArrayList<>();
    }

    public DeviceContext getDeviceContext(String deviceId) {
        return registeredDevices.get(deviceId);
    }

    public static void main(String[] args) throws Exception {
        GatewayServer gateway = new GatewayServer();

        System.out.println("=== Gateway Server Simulation ===\n");

        gateway.registerDevice("device-1");
        byte[] challenge = gateway.generateChallenge("device-1");
        byte[] signature = gateway.signChallengeAsDevice("device-1", challenge);
        gateway.authenticateDevice("device-1", challenge, signature);

        String payload = "23.5:" + System.currentTimeMillis();
        SecurePacket packet = gateway.createSecurePacket("device-1", payload);

        SensorData data = gateway.processPacket(packet);
        System.out.println("Received data: " + data);

        gateway.printStatus();
    }

}