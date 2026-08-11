package com.aquagrid.platform.iot.receiver.spi;

import com.aquagrid.platform.common.error.ErrorCode;
import com.aquagrid.platform.iot.receiver.domain.model.IdentifierType;
import com.aquagrid.platform.iot.receiver.domain.model.InboundPacket;
import org.springframework.core.Ordered;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Proves — or fails to prove — that a packet came from someone entitled to send it.
 *
 * <p>A chain of these runs in {@link Ordered} order and the first that {@linkplain #supports
 * supports} the packet decides it. Ordering matters: strong schemes are tried before weak ones, so
 * a packet bearing both an HMAC signature and an API key is judged on the signature. The
 * trusted-network fallback sorts last precisely so that it can never pre-empt a real credential.
 *
 * <p>An authenticator may also <em>identify</em> the device, and when it can, that binding is worth
 * more than anything in the payload: a per-device token proves which device sent the packet,
 * whereas a DevEUI in a body is a claim anyone within earshot can copy. Such an authenticator
 * returns the identifier alongside its verdict and the device resolver prefers it.
 */
public interface PacketAuthenticator extends Ordered {

    /** Scheme name, recorded on the packet log and used as a metric tag. */
    String scheme();

    /**
     * Whether this authenticator is the one to judge the packet — typically "did it present the
     * credential I understand". Returning true commits: no later authenticator is consulted.
     */
    boolean supports(InboundPacket packet);

    AuthenticationResult authenticate(InboundPacket packet);

    @Override
    default int getOrder() {
        return 0;
    }

    /**
     * The verdict.
     *
     * @param authenticated whether the packet may proceed
     * @param principal     who it was authenticated as — a gateway id, an integration name, a
     *                      device code. Recorded, never trusted for authorisation
     * @param identifiers   device identifiers this authenticator proved, if any
     * @param tenantId      the tenant the credential belongs to, where the credential is
     *                      tenant-scoped. A hint only: the tenant of record is the device's
     * @param failure       the error code when {@code authenticated} is false
     * @param detail        operator-facing reason. Must never quote the credential
     */
    record AuthenticationResult(
            boolean authenticated,
            String principal,
            Map<IdentifierType, String> identifiers,
            java.util.UUID tenantId,
            ErrorCode failure,
            String detail
    ) {
        public AuthenticationResult {
            identifiers = identifiers == null ? Map.of() : Map.copyOf(identifiers);
        }

        public static AuthenticationResult success(String principal) {
            return new AuthenticationResult(true, principal, Map.of(), null, null, null);
        }

        public static AuthenticationResult success(String principal,
                                                   Map<IdentifierType, String> identifiers,
                                                   java.util.UUID tenantId) {
            return new AuthenticationResult(true, principal, identifiers, tenantId, null, null);
        }

        public static AuthenticationResult failure(String detail) {
            return new AuthenticationResult(false, null, Map.of(), null,
                    ErrorCode.RECEIVER_AUTHENTICATION_FAILED, detail);
        }

        public static AuthenticationResult failure(ErrorCode code, String detail) {
            return new AuthenticationResult(false, null, Map.of(), null, code, detail);
        }

        /** Builds an identifier map without the caller writing the null guards. */
        public static Map<IdentifierType, String> identifiers(IdentifierType type, String value) {
            if (value == null || value.isBlank()) {
                return Map.of();
            }
            Map<IdentifierType, String> map = new LinkedHashMap<>();
            map.put(type, value);
            return map;
        }
    }
}
