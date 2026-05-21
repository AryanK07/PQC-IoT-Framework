package com.aryan.pqciotframework.security;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Arrays;

public final class HybridEncryptionSupport {

    private static final int GCM_IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;

    private HybridEncryptionSupport() {
    }

    public static SecretKey generateAesKey(int bits) throws Exception {
        KeyGenerator keyGenerator = KeyGenerator.getInstance("AES");
        keyGenerator.init(bits);
        return keyGenerator.generateKey();
    }

    public static EncryptionEnvelope encryptWithAesGcm(SecretKey secretKey, byte[] plaintext, SecureRandom secureRandom) throws Exception {
        byte[] iv = new byte[GCM_IV_BYTES];
        secureRandom.nextBytes(iv);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
        byte[] ciphertext = cipher.doFinal(plaintext);
        return new EncryptionEnvelope(new byte[0], iv, ciphertext);
    }

    public static byte[] decryptWithAesGcm(SecretKey secretKey, byte[] iv, byte[] ciphertext) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
        return cipher.doFinal(ciphertext);
    }

    public static byte[] packEnvelope(byte[] wrappedKey, byte[] iv, byte[] ciphertext) {
        ByteBuffer buffer = ByteBuffer.allocate(8 + wrappedKey.length + iv.length + ciphertext.length);
        buffer.putInt(wrappedKey.length);
        buffer.put(wrappedKey);
        buffer.putInt(iv.length);
        buffer.put(iv);
        buffer.put(ciphertext);
        return buffer.array();
    }

    public static EncryptionEnvelope unpackEnvelope(byte[] payload) {
        ByteBuffer buffer = ByteBuffer.wrap(payload);
        int wrappedKeyLength = buffer.getInt();
        byte[] wrappedKey = new byte[wrappedKeyLength];
        buffer.get(wrappedKey);

        int ivLength = buffer.getInt();
        byte[] iv = new byte[ivLength];
        buffer.get(iv);

        byte[] ciphertext = new byte[buffer.remaining()];
        buffer.get(ciphertext);
        return new EncryptionEnvelope(wrappedKey, iv, ciphertext);
    }

    public static final class EncryptionEnvelope {
        private final byte[] wrappedKey;
        private final byte[] iv;
        private final byte[] ciphertext;

        public EncryptionEnvelope(byte[] wrappedKey, byte[] iv, byte[] ciphertext) {
            this.wrappedKey = Arrays.copyOf(wrappedKey, wrappedKey.length);
            this.iv = Arrays.copyOf(iv, iv.length);
            this.ciphertext = Arrays.copyOf(ciphertext, ciphertext.length);
        }

        public byte[] getWrappedKey() {
            return Arrays.copyOf(wrappedKey, wrappedKey.length);
        }

        public byte[] getIv() {
            return Arrays.copyOf(iv, iv.length);
        }

        public byte[] getCiphertext() {
            return Arrays.copyOf(ciphertext, ciphertext.length);
        }
    }
}
