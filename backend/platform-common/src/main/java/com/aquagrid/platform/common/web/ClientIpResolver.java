package com.aquagrid.platform.common.web;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * Determines the real client IP behind reverse proxies, safely.
 *
 * <p>This class is security-critical: account lockout, login rate limiting and the audit trail all
 * key on its result. The naive implementation — reading {@code X-Forwarded-For} unconditionally —
 * lets an attacker present a different value on every request and thereby evade every per-IP
 * control, and lets them poison the audit trail with an innocent third party's address.
 *
 * <p>Rules applied here:
 * <ol>
 *   <li>If the TCP peer ({@code getRemoteAddr()}) is <b>not</b> a configured trusted proxy, the
 *       forwarding headers are ignored entirely — the peer address is the truth.</li>
 *   <li>Otherwise {@code X-Forwarded-For} is walked <b>right to left</b> (nearest hop first) and
 *       the first address that is not itself a trusted proxy is returned. Anything the client
 *       prepended sits to the left of that and is never reached.</li>
 *   <li>If every hop is trusted, the peer address is returned.</li>
 * </ol>
 *
 * <p>With no trusted proxies configured (the default), the peer address is always used, which is
 * the correct behaviour for a directly-exposed service.
 */
@Slf4j
public class ClientIpResolver {

    private static final String X_FORWARDED_FOR = "X-Forwarded-For";
    private static final String X_REAL_IP = "X-Real-IP";
    private static final String UNKNOWN = "unknown";

    private final List<IpSubnet> trustedProxies;

    public ClientIpResolver(List<String> trustedProxyCidrs) {
        this.trustedProxies = trustedProxyCidrs == null
                ? List.of()
                : trustedProxyCidrs.stream().filter(cidr -> !cidr.isBlank()).map(IpSubnet::parse).toList();
        if (this.trustedProxies.isEmpty()) {
            log.info("No trusted proxies configured — forwarding headers will be ignored");
        } else {
            log.info("Trusted proxy ranges: {}", this.trustedProxies);
        }
    }

    public String resolve(HttpServletRequest request) {
        String peer = request.getRemoteAddr();
        if (peer == null || peer.isBlank()) {
            return UNKNOWN;
        }
        if (!isTrustedProxy(peer)) {
            return normalise(peer);
        }

        String forwardedFor = request.getHeader(X_FORWARDED_FOR);
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            String[] hops = forwardedFor.split(",");
            for (int i = hops.length - 1; i >= 0; i--) {
                String hop = hops[i].trim();
                if (hop.isEmpty() || UNKNOWN.equalsIgnoreCase(hop)) {
                    continue;
                }
                if (!isTrustedProxy(hop)) {
                    return normalise(hop);
                }
            }
        }

        String realIp = request.getHeader(X_REAL_IP);
        if (realIp != null && !realIp.isBlank() && !isTrustedProxy(realIp.trim())) {
            return normalise(realIp.trim());
        }
        return normalise(peer);
    }

    private boolean isTrustedProxy(String candidate) {
        for (IpSubnet subnet : trustedProxies) {
            if (subnet.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    /** Strips brackets/zone/port so the value is storable in a PostgreSQL {@code inet} column. */
    private String normalise(String value) {
        String result = value.trim();
        if (result.startsWith("[")) {
            int close = result.indexOf(']');
            if (close > 0) {
                return result.substring(1, close);
            }
        }
        // "1.2.3.4:5678" — a port is only unambiguous for IPv4 (IPv6 uses multiple colons).
        int firstColon = result.indexOf(':');
        if (firstColon > 0 && result.indexOf(':', firstColon + 1) < 0) {
            return result.substring(0, firstColon);
        }
        int percent = result.indexOf('%');
        return percent > 0 ? result.substring(0, percent) : result;
    }
}
