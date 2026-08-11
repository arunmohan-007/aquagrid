package com.aquagrid.platform.common.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Security regression suite for client IP resolution.
 *
 * <p>Account lockout, login rate limiting and the audit trail all key on this value. If an attacker
 * can control it, they can evade every per-IP control by varying a header, and can attribute their
 * activity to an innocent third party. These tests exist to make that regression impossible to
 * merge.
 */
class ClientIpResolverTest {

    @Nested
    @DisplayName("With no trusted proxies (directly exposed deployment)")
    class NoTrustedProxies {

        private final ClientIpResolver resolver = new ClientIpResolver(List.of());

        @Test
        @DisplayName("uses the TCP peer address")
        void usesPeerAddress() {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setRemoteAddr("203.0.113.9");

            assertThat(resolver.resolve(request)).isEqualTo("203.0.113.9");
        }

        @Test
        @DisplayName("ignores a spoofed X-Forwarded-For entirely")
        void ignoresSpoofedForwardedFor() {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setRemoteAddr("203.0.113.9");
            request.addHeader("X-Forwarded-For", "1.2.3.4");

            assertThat(resolver.resolve(request)).isEqualTo("203.0.113.9");
        }

        @Test
        @DisplayName("ignores a spoofed X-Real-IP")
        void ignoresSpoofedRealIp() {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setRemoteAddr("203.0.113.9");
            request.addHeader("X-Real-IP", "1.2.3.4");

            assertThat(resolver.resolve(request)).isEqualTo("203.0.113.9");
        }
    }

    @Nested
    @DisplayName("Behind a trusted proxy")
    class BehindTrustedProxy {

        private final ClientIpResolver resolver =
                new ClientIpResolver(List.of("10.0.0.0/8", "172.16.0.0/12"));

        @Test
        @DisplayName("returns the client hop from X-Forwarded-For")
        void returnsClientHop() {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setRemoteAddr("10.0.0.5");
            request.addHeader("X-Forwarded-For", "203.0.113.9");

            assertThat(resolver.resolve(request)).isEqualTo("203.0.113.9");
        }

        @Test
        @DisplayName("walks right to left and stops at the first untrusted hop")
        void walksRightToLeft() {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setRemoteAddr("10.0.0.5");
            // The attacker prepended 1.2.3.4; the real client is 203.0.113.9 and the rest are
            // our own proxies. Right-to-left traversal reaches the real client first.
            request.addHeader("X-Forwarded-For", "1.2.3.4, 203.0.113.9, 172.16.0.7, 10.0.0.5");

            assertThat(resolver.resolve(request)).isEqualTo("203.0.113.9");
        }

        @Test
        @DisplayName("a client-injected header cannot forge the address")
        void clientInjectedHeaderCannotForge() {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setRemoteAddr("10.0.0.5");
            // The proxy appends the genuine peer; anything the client sent sits to its left.
            request.addHeader("X-Forwarded-For", "9.9.9.9, 8.8.8.8, 203.0.113.9");

            assertThat(resolver.resolve(request)).isEqualTo("203.0.113.9");
        }

        @Test
        @DisplayName("falls back to the peer when every hop is a trusted proxy")
        void fallsBackWhenAllHopsTrusted() {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setRemoteAddr("10.0.0.5");
            request.addHeader("X-Forwarded-For", "10.0.0.7, 10.0.0.5");

            assertThat(resolver.resolve(request)).isEqualTo("10.0.0.5");
        }

        @Test
        @DisplayName("tolerates malformed and empty hops")
        void toleratesMalformedHops() {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setRemoteAddr("10.0.0.5");
            request.addHeader("X-Forwarded-For", "unknown, , 203.0.113.9, 10.0.0.5");

            assertThat(resolver.resolve(request)).isEqualTo("203.0.113.9");
        }

        @Test
        @DisplayName("strips the port from an IPv4:port hop so the value fits an inet column")
        void stripsPort() {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setRemoteAddr("10.0.0.5");
            request.addHeader("X-Forwarded-For", "203.0.113.9:51234");

            assertThat(resolver.resolve(request)).isEqualTo("203.0.113.9");
        }

        @Test
        @DisplayName("handles a bracketed IPv6 hop")
        void handlesIpv6() {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setRemoteAddr("10.0.0.5");
            request.addHeader("X-Forwarded-For", "[2001:db8::1]");

            assertThat(resolver.resolve(request)).isEqualTo("2001:db8::1");
        }
    }

    @Nested
    @DisplayName("CIDR matching")
    class CidrMatching {

        @Test
        void matchesWithinRange() {
            assertThat(IpSubnet.parse("10.0.0.0/8").contains("10.255.3.1")).isTrue();
            assertThat(IpSubnet.parse("172.16.0.0/12").contains("172.31.255.254")).isTrue();
            assertThat(IpSubnet.parse("192.168.1.0/24").contains("192.168.1.200")).isTrue();
        }

        @Test
        void rejectsOutsideRange() {
            assertThat(IpSubnet.parse("10.0.0.0/8").contains("11.0.0.1")).isFalse();
            assertThat(IpSubnet.parse("172.16.0.0/12").contains("172.32.0.1")).isFalse();
            assertThat(IpSubnet.parse("192.168.1.0/24").contains("192.168.2.1")).isFalse();
        }

        @Test
        void doesNotMatchAcrossAddressFamilies() {
            assertThat(IpSubnet.parse("10.0.0.0/8").contains("::1")).isFalse();
            assertThat(IpSubnet.parse("::1/128").contains("10.0.0.1")).isFalse();
        }

        @Test
        void treatsBareAddressAsSingleHost() {
            assertThat(IpSubnet.parse("203.0.113.9").contains("203.0.113.9")).isTrue();
            assertThat(IpSubnet.parse("203.0.113.9").contains("203.0.113.10")).isFalse();
        }
    }
}
