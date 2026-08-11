package com.aquagrid.platform.identity.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * A single capability, in {@code domain:resource:action} form.
 *
 * <p>Global rather than tenant-owned: the catalogue is defined by the product, so
 * {@code @PreAuthorize} can reference compile-time constants. What varies per tenant is which
 * roles hold which permissions.
 */
@Getter
@Setter
@Entity
@Table(name = "permissions", schema = "identity")
public class Permission {

    @Id
    @GeneratedValue
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "code", nullable = false, length = 120, updatable = false)
    private String code;

    @Column(name = "domain", nullable = false, length = 40)
    private String domain;

    @Column(name = "resource", nullable = false, length = 60)
    private String resource;

    @Column(name = "action", nullable = false, length = 40)
    private String action;

    @Column(name = "description", length = 300)
    private String description;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
