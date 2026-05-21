package com.aryan.pqciotframework.security;

import java.security.PrivateKey;
import java.security.PublicKey;

public interface SecurityService {

    // Generate both encryption and signature key pairs for the selected mode
    CryptoKeyBundle generateKeyBundle() throws Exception;

    // Encryption / Decryption
    byte[] encryptData(PublicKey publicKey, byte[] plaintext) throws Exception;
    byte[] decryptData(PrivateKey privateKey, byte[] ciphertext) throws Exception;

    // Digital Signature
    byte[] signData(PrivateKey privateKey, byte[] data) throws Exception;
    boolean verifySignature(PublicKey publicKey, byte[] data, byte[] signature) throws Exception;

    // Mode identification
    String getModeName();

    // Performance measurement
    long measureKeyGenTime() throws Exception;
    long measureEncryptTime(PublicKey publicKey, byte[] data) throws Exception;
    long measureSignTime(PrivateKey privateKey, byte[] data) throws Exception;
    long measureVerifyTime(PublicKey publicKey, byte[] data, byte[] signature) throws Exception;
}