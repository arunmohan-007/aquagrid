package com.aquagrid.platform.security.jwt;

import com.aquagrid.platform.common.web.ApiPaths;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Map;

/**
 * Publishes the token-signing public keys as a JWKS document.
 *
 * <p>This endpoint is what makes the "microservice-ready" claim concrete. When the IoT ingestion or
 * analytics module is extracted into its own service, it validates AquaGrid access tokens by
 * fetching this document — offline, with no call back to the identity service on the request path,
 * and with no shared secret to distribute or rotate.
 *
 * <p>It exposes public key material only; publishing it is the intended use.
 */
@Tag(name = "Well-Known", description = "Public discovery documents")
@RestController
@RequestMapping(ApiPaths.WELL_KNOWN)
@RequiredArgsConstructor
public class JwksController {

    private final JwtKeyProvider keyProvider;

    @GetMapping(value = "/jwks.json", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "JSON Web Key Set",
            description = "Public keys used to verify AquaGrid access tokens (RFC 7517).")
    public ResponseEntity<Map<String, Object>> jwks() {
        return ResponseEntity.ok()
                // Cached, but not for long: a key rotation must propagate within minutes.
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic())
                .body(keyProvider.getJwkSet().toJSONObject());
    }
}
