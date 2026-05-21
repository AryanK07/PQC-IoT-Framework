package com.aryan.pqciotframework.security;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import java.security.*;

public class ClassicalSecurityService implements SecurityService {

    private static final String RSA_ALGORITHM = "RSA";
    private static final String ECDSA_ALGORITHM = "SHA256withECDSA";
    private static final int RSA_KEY_SIZE = 2048;
    private static final int AES_KEY_BITS = 256;

    // ===== KEY GENERATION =====

    @Override
    public CryptoKeyBundle generateKeyBundle() throws Exception {
        KeyPair encryptionKeyPair = generateRSAKeyPair();
        KeyPair signatureKeyPair = generateECDSAKeyPair();
        return new CryptoKeyBundle(encryptionKeyPair, signatureKeyPair);
    }

    public KeyPair generateRSAKeyPair() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance(RSA_ALGORITHM);
        kpg.initialize(RSA_KEY_SIZE);
        return kpg.generateKeyPair();
    }

    public KeyPair generateECDSAKeyPair() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(256);
        return kpg.generateKeyPair();
    }

    // ===== ENCRYPTION / DECRYPTION =====

    @Override
    public byte[] encryptData(PublicKey publicKey, byte[] plaintext) throws Exception {
        SecretKey aesKey = HybridEncryptionSupport.generateAesKey(AES_KEY_BITS);
        HybridEncryptionSupport.EncryptionEnvelope envelope = HybridEncryptionSupport.encryptWithAesGcm(aesKey, plaintext, new SecureRandom());

        Cipher keyWrapCipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
        keyWrapCipher.init(Cipher.ENCRYPT_MODE, publicKey);
        byte[] wrappedKey = keyWrapCipher.doFinal(aesKey.getEncoded());

        return HybridEncryptionSupport.packEnvelope(wrappedKey, envelope.getIv(), envelope.getCiphertext());
    }

    @Override
    public byte[] decryptData(PrivateKey privateKey, byte[] ciphertext) throws Exception {
        HybridEncryptionSupport.EncryptionEnvelope envelope = HybridEncryptionSupport.unpackEnvelope(ciphertext);

        Cipher keyUnwrapCipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
        keyUnwrapCipher.init(Cipher.DECRYPT_MODE, privateKey);
        byte[] aesKeyBytes = keyUnwrapCipher.doFinal(envelope.getWrappedKey());

        SecretKey aesKey = new javax.crypto.spec.SecretKeySpec(aesKeyBytes, "AES");
        return HybridEncryptionSupport.decryptWithAesGcm(aesKey, envelope.getIv(), envelope.getCiphertext());
    }

    // ===== DIGITAL SIGNATURE =====

    @Override
    public byte[] signData(PrivateKey privateKey, byte[] data) throws Exception {
        Signature sig = Signature.getInstance(ECDSA_ALGORITHM);
        sig.initSign(privateKey);
        sig.update(data);
        return sig.sign();
    }

    @Override
    public boolean verifySignature(PublicKey publicKey, byte[] data, byte[] signature) throws Exception {
        Signature sig = Signature.getInstance(ECDSA_ALGORITHM);
        sig.initVerify(publicKey);
        sig.update(data);
        return sig.verify(signature);
    }

    // ===== MODE IDENTIFICATION =====

    @Override
    public String getModeName() {
        return "Classical (RSA-2048 + ECDSA-256)";
    }

    // ===== PERFORMANCE MEASUREMENT =====

    @Override
    public long measureKeyGenTime() throws Exception {
        long start = System.nanoTime();
        generateKeyBundle();
        return (System.nanoTime() - start) / 1_000_000;
    }

    @Override
    public long measureEncryptTime(PublicKey publicKey, byte[] data) throws Exception {
        long start = System.nanoTime();
        encryptData(publicKey, data);
        return (System.nanoTime() - start) / 1_000_000;
    }

    @Override
    public long measureSignTime(PrivateKey privateKey, byte[] data) throws Exception {
        // ECDSA needs EC key, not RSA key
        KeyPair ecPair = generateECDSAKeyPair();
        long start = System.nanoTime();
        signData(ecPair.getPrivate(), data);
        return (System.nanoTime() - start) / 1_000_000;
    }

    @Override
    public long measureVerifyTime(PublicKey publicKey, byte[] data, byte[] signature) throws Exception {
        KeyPair ecPair = generateECDSAKeyPair();
        byte[] ecSig = signData(ecPair.getPrivate(), data);
        long start = System.nanoTime();
        verifySignature(ecPair.getPublic(), data, ecSig);
        return (System.nanoTime() - start) / 1_000_000;
    }

    public static void main(String[] args) throws Exception {
        ClassicalSecurityService service = new ClassicalSecurityService();

        System.out.println("=== Testing " + service.getModeName() + " ===\n");

        // RSA encrypt/decrypt
        System.out.println("--- RSA Encryption ---");
        KeyPair rsaPair = service.generateRSAKeyPair();
        byte[] plaintext = "Hello Classical".getBytes();
        byte[] ciphertext = service.encryptData(rsaPair.getPublic(), plaintext);
        byte[] decrypted = service.decryptData(rsaPair.getPrivate(), ciphertext);
        System.out.println("Original:  " + new String(plaintext));
        System.out.println("Decrypted: " + new String(decrypted));

        // ECDSA sign/verify
        System.out.println("\n--- ECDSA Signature ---");
        KeyPair ecPair = service.generateECDSAKeyPair();
        byte[] signature = service.signData(ecPair.getPrivate(), plaintext);
        boolean verified = service.verifySignature(ecPair.getPublic(), plaintext, signature);
        System.out.println("Signature valid: " + verified);

        // Performance
        System.out.println("\n--- Performance ---");
        System.out.println("RSA KeyGen:  " + service.measureKeyGenTime() + " ms");
        System.out.println("RSA Encrypt: " + service.measureEncryptTime(rsaPair.getPublic(), plaintext) + " ms");
        System.out.println("ECDSA Sign:  " + service.measureSignTime(ecPair.getPrivate(), plaintext) + " ms");
    }
}