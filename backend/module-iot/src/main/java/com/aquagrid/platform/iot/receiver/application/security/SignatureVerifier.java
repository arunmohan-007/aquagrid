package com.aquagrid.platform.iot.receiver.application.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Verifies HMAC payload signatures.
 *
 * <p>The signature covers the payload bytes concatenated with the nonce and timestamp, not the
 * payload alone. Signing only the body produces a token that is valid forever and reusable by
 * anyone who has seen it once — the signature would prove the bytes came from the device, which is
 * true of a captured packet too. Binding the nonce and timestamp into the signed material is what
 * makes {@link ReplayProtectionService}'s check meaningful: without it an attacker simply strips
 * the nonce and supplies their own.
 *
 * <p>Comparison is constant-time. A byte-by-byte comparison that returns early leaks, through
 * timing, how many leading bytes of a guess were right, which turns forging a signature from
 * infeasible into a few thousand requests.
 */
@Slf4j
@Service
public class SignatureVerifier {

    public static final String DEFAULT_ALGORITHM = "HmacSHA256";

    /**
     * @param secretHex the device's shared secret, hex-encoded as stored in {@code provisioning}
     * @param signature the presented signature, hex or base64 — both are in the wild and the
     *                  encoding is not a security property, so both are accepted
     * @return true when the signature matches
     */
    public boolean verify(byte[] payload, String nonce, String timestamp,
                          String secretHex, String signature, String algorithm) {
        if (secretHex == null || secretHex.isBlank() || signature == null || signature.isBlank()) {
            return false;
        }
        try {
            byte[] key = HexFormat.of().parseHex(secretHex.trim());
            String algo = algorithm == null || algorithm.isBlank() ? DEFAULT_ALGORITHM : algorithm;

            Mac mac = Mac.getInstance(algo);
            mac.init(new SecretKeySpec(key, algo));
            mac.update(payload);
            if (nonce != null) {
                mac.update(nonce.getBytes(StandardCharsets.UTF_8));
            }
            if (timestamp != null) {
                mac.update(timestamp.getBytes(StandardCharsets.UTF_8));
            }
            byte[] expected = mac.doFinal();

            byte[] presented = decode(signature.trim());
            return presented != null && MessageDigest.isEqual(expected, presented);
        } catch (Exception e) {
            // A malformed key, an unknown algorithm or an undecodable signature are all "this
            // packet is not signed by us". Logged at debug: on a public endpoint this is traffic,
            // not a fault, and logging it at warn hands an attacker a way to fill the disk.
            log.debug("Signature verification failed: {}", e.getMessage());
            return false;
        }
    }

    private static byte[] decode(String signature) {
        try {
            return HexFormat.of().parseHex(signature);
        } catch (IllegalArgumentException notHex) {
            try {
                return Base64.getDecoder().decode(signature);
            } catch (IllegalArgumentException notBase64) {
                return null;
            }
        }
    }
}
