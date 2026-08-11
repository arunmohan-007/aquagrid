package com.aquagrid.platform.common.web;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;

/**
 * An immutable IPv4/IPv6 CIDR block with a containment test.
 *
 * <p>Written by hand rather than pulled from a library because it sits on the security-critical
 * path of {@link ClientIpResolver} and must have no surprising parsing behaviour.
 */
public final class IpSubnet {

    private final byte[] network;
    private final int prefixLength;

    private IpSubnet(byte[] network, int prefixLength) {
        this.network = network;
        this.prefixLength = prefixLength;
    }

    /** Parses {@code 10.0.0.0/8}, {@code ::1/128} or a bare address (treated as a /32 or /128). */
    public static IpSubnet parse(String cidr) {
        String value = cidr.trim();
        int slash = value.indexOf('/');
        String addressPart = slash < 0 ? value : value.substring(0, slash);
        InetAddress address;
        try {
            address = InetAddress.getByName(addressPart);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("Not a valid IP address or CIDR: " + cidr, e);
        }
        byte[] bytes = address.getAddress();
        int maxPrefix = bytes.length * 8;
        int prefix = maxPrefix;
        if (slash >= 0) {
            try {
                prefix = Integer.parseInt(value.substring(slash + 1).trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Not a valid CIDR prefix: " + cidr, e);
            }
            if (prefix < 0 || prefix > maxPrefix) {
                throw new IllegalArgumentException("CIDR prefix out of range: " + cidr);
            }
        }
        return new IpSubnet(maskOff(bytes, prefix), prefix);
    }

    public boolean contains(String ipAddress) {
        if (ipAddress == null || ipAddress.isBlank()) {
            return false;
        }
        byte[] candidate;
        try {
            candidate = InetAddress.getByName(stripZone(ipAddress.trim())).getAddress();
        } catch (UnknownHostException | SecurityException e) {
            return false;
        }
        if (candidate.length != network.length) {
            return false;
        }
        return Arrays.equals(maskOff(candidate, prefixLength), network);
    }

    private static byte[] maskOff(byte[] address, int prefixLength) {
        byte[] masked = address.clone();
        for (int i = 0; i < masked.length; i++) {
            int bitsConsumed = i * 8;
            if (bitsConsumed >= prefixLength) {
                masked[i] = 0;
            } else if (prefixLength - bitsConsumed < 8) {
                int keep = prefixLength - bitsConsumed;
                masked[i] = (byte) (masked[i] & (0xFF << (8 - keep)));
            }
        }
        return masked;
    }

    /** Removes an IPv6 zone index and IPv6 brackets, which {@code InetAddress} rejects. */
    private static String stripZone(String value) {
        String result = value;
        if (result.startsWith("[")) {
            int close = result.indexOf(']');
            result = close > 0 ? result.substring(1, close) : result.substring(1);
        }
        int percent = result.indexOf('%');
        return percent >= 0 ? result.substring(0, percent) : result;
    }

    @Override
    public String toString() {
        try {
            return InetAddress.getByAddress(network).getHostAddress() + "/" + prefixLength;
        } catch (UnknownHostException e) {
            return "invalid/" + prefixLength;
        }
    }
}
