package com.aryan.pqciotframework.security;

import java.security.KeyPair;

public class CryptoKeyBundle {

    private final KeyPair encryptionKeyPair;
    private final KeyPair signatureKeyPair;

    public CryptoKeyBundle(KeyPair encryptionKeyPair, KeyPair signatureKeyPair) {
        this.encryptionKeyPair = encryptionKeyPair;
        this.signatureKeyPair = signatureKeyPair;
    }

    public KeyPair getEncryptionKeyPair() {
        return encryptionKeyPair;
    }

    public KeyPair getSignatureKeyPair() {
        return signatureKeyPair;
    }
}