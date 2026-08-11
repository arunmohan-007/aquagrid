package com.aquagrid.platform.common.crypto;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Field-level encryption configuration.
 *
 * @param masterKey Base64-encoded 256-bit data-encryption key. Supplied by the environment
 *                  (or a KMS/Vault-backed property source in cloud deployments) and never
 *                  committed. Absent in production the application refuses to start.
 */
@Validated
@ConfigurationProperties(prefix = "aquagrid.crypto")
public record CryptoProperties(
        @NotBlank(message = "aquagrid.crypto.master-key must be set (Base64, 32 bytes)")
        String masterKey
) {
}
