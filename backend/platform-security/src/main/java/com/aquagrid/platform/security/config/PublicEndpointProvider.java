package com.aquagrid.platform.security.config;

import java.util.List;

/**
 * Extension point through which a module declares its own unauthenticated endpoints.
 *
 * <p>Without this, {@code SecurityConfig} would have to enumerate the public paths of every module
 * in the platform, which reverses the dependency direction (the kernel would need to know about
 * identity, GIS, IoT…) and turns the security configuration into a merge-conflict magnet that
 * nobody reviews carefully. Here, each module owns and justifies its own exposure, and every
 * implementation is one small, reviewable class.
 */
public interface PublicEndpointProvider {

    List<PublicEndpoint> publicEndpoints();
}
