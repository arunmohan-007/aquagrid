package com.aquagrid.platform.common.crypto;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Authenticated symmetric encryption for secrets that must be recoverable in plaintext —
 * TOTP seeds today, SCADA and MQTT device credentials in later modules.
 *
 * <p>AES-256-GCM is used, not AES-CBC: GCM authenticates the ciphertext, so a tampered database
 * row fails to decrypt instead of silently yielding attacker-influenced plaintext. A fresh
 * 96-bit IV is generated per encryption (IV reuse under GCM is catastrophic) and prefixed to the
 * ciphertext. The envelope is versioned so the key or algorithm can be rotated without a
 * flag-day migration.
 *
 * <p>Wire format: {@code base64( version(1) || iv(12) || ciphertext || tag(16) )}.
 */
@Slf4j
@Service
public class CryptoService {

    private static final byte ENVELOPE_VERSION = 1;
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKey key;

    public CryptoService(CryptoProperties properties) {
        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(properties.masterKey().trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("aquagrid.crypto.master-key is not valid Base64", e);
        }
        if (keyBytes.length != 32) {
            throw new IllegalStateException(
                    "aquagrid.crypto.master-key must decode to exactly 32 bytes (got %d)"
                            .formatted(keyBytes.length));
        }
        this.key = new SecretKeySpec(keyBytes, "AES");
    }

    public String encrypt(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            ByteBuffer envelope = ByteBuffer.allocate(1 + IV_LENGTH + ciphertext.length);
            envelope.put(ENVELOPE_VERSION).put(iv).put(ciphertext);
            return Base64.getEncoder().encodeToString(envelope.array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    public String decrypt(String envelopeBase64) {
        if (envelopeBase64 == null) {
            return null;
        }
        try {
            ByteBuffer envelope = ByteBuffer.wrap(Base64.getDecoder().decode(envelopeBase64));
            byte version = envelope.get();
            if (version != ENVELOPE_VERSION) {
                throw new IllegalStateException("Unsupported ciphertext envelope version " + version);
            }
            byte[] iv = new byte[IV_LENGTH];
            envelope.get(iv);
            byte[] ciphertext = new byte[envelope.remaining()];
            envelope.get(ciphertext);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException | java.nio.BufferUnderflowException e) {
            throw new IllegalStateException("Decryption failed — ciphertext is corrupt or the key changed", e);
        }
    }
}
