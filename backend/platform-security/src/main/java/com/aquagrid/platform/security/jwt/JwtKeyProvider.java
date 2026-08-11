package com.aquagrid.platform.security.jwt;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Supplies the RSA key pair used to sign and verify access tokens.
 *
 * <p>RS256 rather than HS256 is the decision that makes later service extraction cheap: the private
 * key never leaves this module, and any other service validates tokens offline against the public
 * JWKS. With a shared HMAC secret, every service that can <em>verify</em> a token can also
 * <em>mint</em> one, so a single compromised read-only service becomes a full identity compromise.
 *
 * <p>If no key is configured, an ephemeral pair is generated and a loud warning is logged. That is
 * a development convenience only: on restart every issued token becomes invalid, and in a
 * multi-replica deployment replicas would not accept each other's tokens. Production configuration
 * is validated at startup by {@code SecurityConfig}.
 */
@Slf4j
@Getter
@Component
public class JwtKeyProvider {

    private final RSAPublicKey publicKey;
    private final RSAPrivateKey privateKey;
    private final String keyId;
    private final JWKSet jwkSet;
    private final boolean ephemeral;

    public JwtKeyProvider(JwtProperties properties) {
        this.keyId = properties.keyId();

        boolean configured = isPresent(properties.privateKeyPem()) && isPresent(properties.publicKeyPem());
        if (configured) {
            this.privateKey = readPrivateKey(properties.privateKeyPem());
            this.publicKey = readPublicKey(properties.publicKeyPem());
            this.ephemeral = false;
        } else {
            KeyPair generated = generateKeyPair();
            this.privateKey = (RSAPrivateKey) generated.getPrivate();
            this.publicKey = (RSAPublicKey) generated.getPublic();
            this.ephemeral = true;
            log.warn("""
                    ================================================================
                     No JWT signing key configured — an EPHEMERAL key was generated.
                     All sessions are invalidated on restart and replicas will reject
                     each other's tokens. Configure aquagrid.security.jwt.private-key-pem
                     and public-key-pem before any non-development deployment.
                    ================================================================""");
        }

        // The JWK must carry the private key for NimbusJwtEncoder to sign. Without it, the encoder
        // fails with "Expected private JWK but none available" — which is what happens if the
        // builder is given only the public key. The private key is present in both the configured
        // and the ephemeral paths; attaching it here fixes both.
        var builder = new RSAKey.Builder(publicKey)
                .keyID(keyId)
                .keyUse(com.nimbusds.jose.jwk.KeyUse.SIGNATURE)
                .algorithm(com.nimbusds.jose.JWSAlgorithm.RS256);
        if (privateKey != null) {
            builder.privateKey(privateKey);
        }
        this.jwkSet = new JWKSet(builder.build());
    }

    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }

    private static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("RSA key generation is unavailable", e);
        }
    }

    private static RSAPrivateKey readPrivateKey(String pem) {
        try {
            byte[] der = Base64.getMimeDecoder().decode(stripPemArmour(pem));
            return (RSAPrivateKey) KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (NoSuchAlgorithmException | InvalidKeySpecException | IllegalArgumentException e) {
            throw new IllegalStateException(
                    "aquagrid.security.jwt.private-key-pem is not a valid PKCS#8 RSA private key", e);
        }
    }

    private static RSAPublicKey readPublicKey(String pem) {
        try {
            byte[] der = Base64.getMimeDecoder().decode(stripPemArmour(pem));
            return (RSAPublicKey) KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(der));
        } catch (NoSuchAlgorithmException | InvalidKeySpecException | IllegalArgumentException e) {
            throw new IllegalStateException(
                    "aquagrid.security.jwt.public-key-pem is not a valid X.509 RSA public key", e);
        }
    }

    private static String stripPemArmour(String pem) {
        return pem.replaceAll("-----BEGIN (.*)-----", "")
                .replaceAll("-----END (.*)-----", "")
                .replaceAll("\\s", "");
    }
}
