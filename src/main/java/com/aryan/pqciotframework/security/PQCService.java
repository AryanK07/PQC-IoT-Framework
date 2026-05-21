package com.aryan.pqciotframework.security;

import org.bouncycastle.jcajce.SecretKeyWithEncapsulation;
import org.bouncycastle.jcajce.spec.KEMExtractSpec;
import org.bouncycastle.jcajce.spec.KEMGenerateSpec;
import org.bouncycastle.pqc.jcajce.provider.BouncyCastlePQCProvider;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Security;

public class PQCService implements SecurityService {

    private static final String KEM_ALGORITHM = "Kyber512";
    private static final String SIGNATURE_ALGORITHM = "Dilithium2";
    private static final int AES_KEY_BITS = 256;

    private final SecureRandom secureRandom = new SecureRandom();

    static {
        Security.addProvider(new BouncyCastlePQCProvider());
    }

    // ===== KEY GENERATION =====

    @Override
    public CryptoKeyBundle generateKeyBundle() throws Exception {
        KeyPair encryptionKeyPair = generateKyberKeyPair();
        KeyPair signatureKeyPair = generateDilithiumKeyPair();
        return new CryptoKeyBundle(encryptionKeyPair, signatureKeyPair);
    }

    public KeyPair generateKyberKeyPair() throws Exception {
        KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance(KEM_ALGORITHM, "BCPQC");
        return keyPairGenerator.generateKeyPair();
    }

    public KeyPair generateDilithiumKeyPair() throws Exception {
        KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance(SIGNATURE_ALGORITHM, "BCPQC");
        return keyPairGenerator.generateKeyPair();
    }

    // ===== ENCRYPTION / DECRYPTION =====

    @Override
    public byte[] encryptData(PublicKey publicKey, byte[] plaintext) throws Exception {
        return encryptKyber(publicKey, plaintext);
    }

    @Override
    public byte[] decryptData(PrivateKey privateKey, byte[] ciphertext) throws Exception {
        return decryptKyber(privateKey, ciphertext);
    }

    public byte[] encryptKyber(PublicKey publicKey, byte[] plaintext) throws Exception {
        KeyGenerator keyGenerator = KeyGenerator.getInstance(KEM_ALGORITHM, "BCPQC");
        keyGenerator.init(new KEMGenerateSpec(publicKey, "AES", AES_KEY_BITS), secureRandom);
        SecretKeyWithEncapsulation secretKeyWithEncapsulation = (SecretKeyWithEncapsulation) keyGenerator.generateKey();

        SecretKey aesKey = secretKeyWithEncapsulation;
        HybridEncryptionSupport.EncryptionEnvelope envelope = HybridEncryptionSupport.encryptWithAesGcm(aesKey, plaintext, secureRandom);
        return HybridEncryptionSupport.packEnvelope(
                secretKeyWithEncapsulation.getEncapsulation(),
                envelope.getIv(),
                envelope.getCiphertext()
        );
    }

    public byte[] decryptKyber(PrivateKey privateKey, byte[] ciphertext) throws Exception {
        HybridEncryptionSupport.EncryptionEnvelope envelope = HybridEncryptionSupport.unpackEnvelope(ciphertext);

        KeyGenerator keyGenerator = KeyGenerator.getInstance(KEM_ALGORITHM, "BCPQC");
        keyGenerator.init(new KEMExtractSpec(privateKey, envelope.getWrappedKey(), "AES", AES_KEY_BITS), secureRandom);
        SecretKeyWithEncapsulation secretKeyWithEncapsulation = (SecretKeyWithEncapsulation) keyGenerator.generateKey();

        return HybridEncryptionSupport.decryptWithAesGcm(
                secretKeyWithEncapsulation,
                envelope.getIv(),
                envelope.getCiphertext()
        );
    }

    // ===== DIGITAL SIGNATURE =====

    @Override
    public byte[] signData(PrivateKey privateKey, byte[] data) throws Exception {
        java.security.Signature sig = java.security.Signature.getInstance("Dilithium", "BCPQC");
        sig.initSign(privateKey);
        sig.update(data);
        return sig.sign();
    }

    @Override
    public boolean verifySignature(PublicKey publicKey, byte[] data, byte[] signature) throws Exception {
        java.security.Signature sig = java.security.Signature.getInstance("Dilithium", "BCPQC");
        sig.initVerify(publicKey);
        sig.update(data);
        return sig.verify(signature);
    }

    // ===== MODE IDENTIFICATION =====

    @Override
    public String getModeName() {
        return "PQC (Kyber512 KEM + Dilithium2 signature)";
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
        encryptKyber(publicKey, data);
        return (System.nanoTime() - start) / 1_000_000;
    }

    @Override
    public long measureSignTime(PrivateKey privateKey, byte[] data) throws Exception {
        long start = System.nanoTime();
        signData(privateKey, data);
        return (System.nanoTime() - start) / 1_000_000;
    }

    @Override
    public long measureVerifyTime(PublicKey publicKey, byte[] data, byte[] signature) throws Exception {
        long start = System.nanoTime();
        verifySignature(publicKey, data, signature);
        return (System.nanoTime() - start) / 1_000_000;
    }

    public static void main(String[] args) throws Exception {
        PQCService service = new PQCService();

        System.out.println("=== Testing " + service.getModeName() + " ===\n");

        System.out.println("--- Kyber Encryption ---");
        KeyPair kyberPair = service.generateKyberKeyPair();
        byte[] plaintext = "Hello PQC".getBytes();
        byte[] ciphertext = service.encryptKyber(kyberPair.getPublic(), plaintext);
        byte[] decrypted = service.decryptKyber(kyberPair.getPrivate(), ciphertext);
        System.out.println("Original:  " + new String(plaintext));
        System.out.println("Decrypted: " + new String(decrypted));

        System.out.println("\n--- Dilithium Signature ---");
        KeyPair dilithiumPair = service.generateDilithiumKeyPair();
        byte[] signature = service.signData(dilithiumPair.getPrivate(), plaintext);
        boolean verified = service.verifySignature(dilithiumPair.getPublic(), plaintext, signature);
        System.out.println("Signature valid: " + verified);

        System.out.println("\n--- Performance ---");
        System.out.println("Dilithium KeyGen: " + service.measureKeyGenTime() + " ms");
        System.out.println("Kyber Encrypt:    " + service.measureEncryptTime(kyberPair.getPublic(), plaintext) + " ms");
        System.out.println("Dilithium Sign:   " + service.measureSignTime(dilithiumPair.getPrivate(), plaintext) + " ms");
    }
}