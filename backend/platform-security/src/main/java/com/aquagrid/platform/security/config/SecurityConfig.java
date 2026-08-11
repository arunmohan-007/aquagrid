package com.aquagrid.platform.security.config;

import com.aquagrid.platform.common.config.WebProperties;
import com.aquagrid.platform.security.jwt.JwtKeyProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.header.writers.XXssProtectionHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The platform's HTTP security policy.
 *
 * <p>Design notes on the decisions that matter:
 *
 * <ul>
 *   <li><b>Stateless.</b> {@code SessionCreationPolicy.STATELESS} — no {@code JSESSIONID}, no
 *       sticky sessions, so API replicas scale horizontally and a session-fixation class of bug
 *       cannot exist.</li>
 *   <li><b>CSRF disabled, safely.</b> CSRF protection defends cookie-borne <em>ambient</em>
 *       authority. API calls here authenticate with an {@code Authorization} header, which a
 *       cross-site form cannot set. The one cookie we do use — the refresh token — is
 *       {@code SameSite=Strict} and scoped to {@code /api/v1/auth}, so a cross-site request never
 *       carries it. Disabling CSRF without both of those properties would be a vulnerability.</li>
 *   <li><b>Public endpoints are contributed by modules</b> through {@link PublicEndpointProvider},
 *       not enumerated here.</li>
 *   <li><b>Everything else requires authentication</b>, and every endpoint additionally carries an
 *       explicit {@code @PreAuthorize}. URL-level rules alone are too coarse for a platform with
 *       35 modules.</li>
 *   <li><b>Actuator is separated:</b> only {@code /health} and {@code /info} are anonymous;
 *       metrics and every other endpoint require an administrative permission.</li>
 * </ul>
 */
@Slf4j
@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
@RequiredArgsConstructor
public class SecurityConfig {

    private static final String[] OPENAPI_PATHS = {
            "/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**"
    };

    private final WebProperties webProperties;
    private final SecurityProblemHandlers problemHandlers;
    private final PrincipalJwtAuthenticationConverter jwtAuthenticationConverter;
    private final ObjectProvider<PublicEndpointProvider> publicEndpointProviders;
    private final JwtKeyProvider keyProvider;
    private final Environment environment;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        assertProductionKeyMaterial();
        List<PublicEndpoint> publicEndpoints = collectPublicEndpoints();

        http
            .csrf(csrf -> csrf.disable())
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .sessionManagement(session ->
                    session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .anonymous(Customizer.withDefaults())

            .headers(headers -> headers
                    .frameOptions(frame -> frame.deny())
                    .contentTypeOptions(Customizer.withDefaults())
                    .xssProtection(xss -> xss
                            .headerValue(XXssProtectionHeaderWriter.HeaderValue.DISABLED))
                    .referrerPolicy(referrer -> referrer
                            .policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                    .httpStrictTransportSecurity(hsts -> hsts
                            .includeSubDomains(true)
                            .maxAgeInSeconds(31_536_000))
                    .permissionsPolicyHeader(permissions -> permissions
                            .policy("geolocation=(self), camera=(), microphone=(), payment=()"))
                    // The API serves JSON only; a strict CSP costs nothing and blocks any attempt
                    // to render an injected document from an API response.
                    .contentSecurityPolicy(csp -> csp
                            .policyDirectives("default-src 'none'; frame-ancestors 'none'; "
                                    + "base-uri 'none'; form-action 'none'")))

            .authorizeHttpRequests(authorize -> {
                authorize.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll();
                authorize.requestMatchers("/actuator/health", "/actuator/health/**",
                        "/actuator/info").permitAll();
                authorize.requestMatchers("/actuator/**")
                        .hasAuthority(com.aquagrid.platform.security.core.Permissions.SYSTEM_MONITOR);
                if (isApiDocsExposed()) {
                    authorize.requestMatchers(OPENAPI_PATHS).permitAll();
                }
                for (PublicEndpoint endpoint : publicEndpoints) {
                    if (endpoint.method() == null) {
                        authorize.requestMatchers(endpoint.pattern()).permitAll();
                    } else {
                        authorize.requestMatchers(endpoint.method(), endpoint.pattern()).permitAll();
                    }
                }
                authorize.anyRequest().authenticated();
            })

            .oauth2ResourceServer(oauth2 -> oauth2
                    .authenticationEntryPoint(problemHandlers)
                    .accessDeniedHandler(problemHandlers)
                    .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)))

            .exceptionHandling(exceptions -> exceptions
                    .authenticationEntryPoint(problemHandlers)
                    .accessDeniedHandler(problemHandlers))

            // Binds TenantContext once the principal exists, and clears it on the way out.
            .addFilterAfter(new TenantContextFilter(), BearerTokenAuthenticationFilter.class);

        return http.build();
    }

    /**
     * BCrypt at cost 12, wrapped in a delegating encoder.
     *
     * <p>The {@code {bcrypt}} prefix stored with every hash is what makes an algorithm migration
     * possible later: Argon2id can become the new default while existing BCrypt hashes keep
     * verifying and are upgraded on next successful login. A bare {@code BCryptPasswordEncoder}
     * paints the product into a corner that is very expensive to leave.
     *
     * <p>Cost 12 is roughly 250 ms on current server hardware — high enough to make offline
     * cracking expensive, low enough that the login endpoint is not itself a denial-of-service
     * amplifier.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(webProperties.corsOrigins());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of(HttpHeaders.AUTHORIZATION, HttpHeaders.CONTENT_TYPE,
                HttpHeaders.ACCEPT, HttpHeaders.IF_MATCH, "X-Request-Id"));
        configuration.setExposedHeaders(webProperties.corsExposedHeaders());
        // Required so the browser sends and stores the refresh-token cookie.
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3_600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        source.registerCorsConfiguration("/.well-known/**", configuration);
        return source;
    }

    private List<PublicEndpoint> collectPublicEndpoints() {
        List<PublicEndpoint> endpoints = new ArrayList<>();
        publicEndpointProviders.orderedStream()
                .forEach(provider -> endpoints.addAll(provider.publicEndpoints()));
        log.info("Registered {} public endpoint(s): {}", endpoints.size(), endpoints.stream()
                .map(endpoint -> (endpoint.method() == null ? "*" : endpoint.method().name())
                        + " " + endpoint.pattern())
                .toList());
        return endpoints;
    }

    private boolean isApiDocsExposed() {
        return environment.getProperty("aquagrid.openapi.expose-ui", Boolean.class, Boolean.FALSE)
                || isDevelopmentProfile();
    }

    private boolean isDevelopmentProfile() {
        return Arrays.stream(environment.getActiveProfiles())
                .anyMatch(profile -> profile.equals("local") || profile.equals("dev")
                        || profile.equals("test"));
    }

    /**
     * Refuses to start a non-development deployment on an ephemeral signing key.
     *
     * <p>Fail fast at boot, not at 3 a.m. when a rolling restart silently logs out every user and
     * the second replica starts rejecting the first replica's tokens.
     */
    private void assertProductionKeyMaterial() {
        if (keyProvider.isEphemeral() && !isDevelopmentProfile()) {
            throw new IllegalStateException(
                    "Refusing to start: no JWT signing key is configured. Set "
                            + "aquagrid.security.jwt.private-key-pem and public-key-pem "
                            + "(active profiles: " + Arrays.toString(environment.getActiveProfiles()) + ")");
        }
    }

}
