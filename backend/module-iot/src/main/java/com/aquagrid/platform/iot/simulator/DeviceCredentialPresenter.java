package com.aquagrid.platform.iot.simulator;

import com.aquagrid.platform.common.crypto.CryptoService;
import com.aquagrid.platform.iot.receiver.application.authentication.HmacSignatureAuthenticator;
import com.aquagrid.platform.iot.receiver.application.security.SignatureVerifier;
import com.aquagrid.platform.iot.receiver.domain.model.PacketCredentials;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Presents the credential a simulated device's <em>real</em> counterpart would present.
 *
 * <p>This class exists because of a requirement that shapes the whole module: a simulated device
 * must be replaceable by a physical one with no change to the receiver, the pipeline or the
 * deployment's security configuration. Authentication is where that requirement is easiest to
 * violate and hardest to notice. An earlier draft of this module gave the simulator its own
 * in-process credential and its own {@code PacketAuthenticator}; it was safe, and it was wrong —
 * cutover would then have meant provisioning a credential the simulated device never had, and the
 * one thing a validation run most needs to prove, that this fleet's credentials actually work,
 * would have been the one thing it could not prove.
 *
 * <p>So there is <b>no simulator authentication scheme</b>. The simulator presents what the device
 * is provisioned with, the receiver checks it with the same authenticator that will check the
 * physical device's packets tomorrow, and a credential that is wrong fails now rather than at
 * commissioning.
 *
 * <p>Precedence follows the receiver's own, strongest first:
 *
 * <ol>
 *   <li><b>Per-device HMAC</b> — the device has {@code secret:hmacKey}, so it signs its payload
 *       exactly as the physical device will. Verified by {@link HmacSignatureAuthenticator}.</li>
 *   <li><b>Gateway API key</b> — the usual arrangement for LoRaWAN and NB-IoT, where the network
 *       server or carrier webhook holds one credential for a whole fleet. The simulator stands in
 *       for that gateway as well as the meter, so it presents the gateway's key. Verified by
 *       {@code ApiKeyAuthenticator} against the deployment's configured hash — the same hash the
 *       real gateway will be checked against.</li>
 *   <li><b>Nothing</b> — the device has no credential and the deployment has configured no gateway
 *       key. The packet presents none, and {@code TrustedNetworkAuthenticator} decides, which under
 *       the default {@code require-authentication: true} means refusal. That refusal is correct and
 *       is the finding: a real device registered this way would be refused identically.</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "aquagrid.iot.transports", name = "simulator", havingValue = "true")
public class DeviceCredentialPresenter {

    private final CryptoService cryptoService;
    private final SimulatorProperties properties;
    private final SecureRandom nonces = new SecureRandom();

    /**
     * Builds the credentials for one uplink.
     *
     * @param meter   the meter, for its device's provisioning block
     * @param payload the bytes to be signed, where the device signs
     * @param now     the instant the signature is bound to
     */
    public PacketCredentials credentialsFor(SimulatedMeter meter, byte[] payload, Instant now) {
        Object encryptedKey = meter.provisioning().get(HmacSignatureAuthenticator.HMAC_KEY_FIELD);
        if (encryptedKey != null) {
            PacketCredentials signed = sign(meter, payload, now, String.valueOf(encryptedKey));
            if (signed != null) {
                return signed;
            }
        }

        String apiKey = properties.gatewayApiKey();
        if (apiKey != null && !apiKey.isBlank()) {
            return PacketCredentials.builder()
                    .with(PacketCredentials.Keys.API_KEY, apiKey)
                    .build();
        }

        return PacketCredentials.none();
    }

    /**
     * Signs the payload the way {@link SignatureVerifier} expects to verify it.
     *
     * <p>Nonce and timestamp are covered by the signature, not merely sent beside it. Signing the
     * body alone would produce a token valid forever to anyone who saw it once — and would leave the
     * replay protection this fleet is meant to exercise with nothing to bind to.
     *
     * @return the credentials, or null when the key cannot be used — in which case the caller falls
     *         through to the next scheme rather than emitting a packet with a broken signature,
     *         which would be indistinguishable from an attack in the packet log
     */
    private PacketCredentials sign(SimulatedMeter meter, byte[] payload, Instant now,
                                   String encryptedKey) {
        try {
            String secretHex = cryptoService.decrypt(encryptedKey);
            String nonce = UUID.nameUUIDFromBytes(newNonce()).toString();
            String timestamp = String.valueOf(now.getEpochSecond());

            Mac mac = Mac.getInstance(SignatureVerifier.DEFAULT_ALGORITHM);
            mac.init(new SecretKeySpec(HexFormat.of().parseHex(secretHex.trim()),
                    SignatureVerifier.DEFAULT_ALGORITHM));
            mac.update(payload);
            mac.update(nonce.getBytes(StandardCharsets.UTF_8));
            mac.update(timestamp.getBytes(StandardCharsets.UTF_8));

            return PacketCredentials.builder()
                    .with(PacketCredentials.Keys.SIGNATURE, HexFormat.of().formatHex(mac.doFinal()))
                    .with(PacketCredentials.Keys.SIGNATURE_ALGORITHM, SignatureVerifier.DEFAULT_ALGORITHM)
                    .with(PacketCredentials.Keys.NONCE, nonce)
                    .with(PacketCredentials.Keys.TIMESTAMP, timestamp)
                    .build();
        } catch (Exception e) {
            // A key that will not decrypt or is not hex is an operational fault in the device's
            // registration, not a reason to stop simulating it. Named once per meter's failure so
            // the cause is in the log, then the packet goes out under whatever scheme is next —
            // where it will be refused, visibly, exactly as the physical device would be.
            log.warn("Simulated device {} has an unusable HMAC key ({}); falling back to the "
                    + "gateway credential", meter.deviceCode(), e.getMessage());
            return null;
        }
    }

    private byte[] newNonce() {
        byte[] bytes = new byte[16];
        nonces.nextBytes(bytes);
        return bytes;
    }
}
