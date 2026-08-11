package com.aquagrid.platform.iot.receiver.domain.model;

import com.aquagrid.platform.common.error.ErrorCode;

/**
 * A refusal, named in the platform's error vocabulary.
 *
 * <p>Returned rather than thrown. Refusing a packet is the normal case on a public ingestion
 * endpoint — an unregistered meter within radio range of a gateway produces one on every uplink,
 * indefinitely — and exceptions for the common path would cost a stack capture per packet and blur
 * the distinction between "this packet is not for us" and "the receiver is broken", which is the
 * distinction on-call needs at 3 a.m.
 *
 * @param code   the classification, shared by the packet log, the metric tag and the HTTP response
 * @param detail operator-facing explanation. Must never quote a credential or a secret
 */
public record Rejection(ErrorCode code, String detail) {

    public static Rejection of(ErrorCode code, String detail) {
        return new Rejection(code, detail);
    }

    public static Rejection of(ErrorCode code) {
        return new Rejection(code, code.getDefaultMessage());
    }
}
