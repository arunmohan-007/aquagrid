package com.aquagrid.platform.security.config;

import com.aquagrid.platform.security.core.AuthClaims;
import com.aquagrid.platform.security.core.AuthenticatedPrincipal;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Turns a verified JWT into the platform's {@link AuthenticatedPrincipal} and its authorities.
 *
 * <p>Two authority forms are produced:
 * <ul>
 *   <li>permission codes verbatim ({@code gis:pipeline:update}) for {@code hasAuthority(...)} —
 *       the form used by every {@code @PreAuthorize} in the platform;</li>
 *   <li>{@code ROLE_}-prefixed role codes, for the rare coarse check and for Spring idioms that
 *       expect them.</li>
 * </ul>
 *
 * <p>This is also where the token <em>type</em> is enforced. An {@code mfa} token — issued after
 * only the password step — must never authenticate an API call, so anything other than
 * {@code typ=access} is rejected here rather than silently producing a principal.
 */
@Component
public class PrincipalJwtAuthenticationConverter
        implements Converter<Jwt, AbstractAuthenticationToken> {

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        if (!AuthClaims.TYPE_ACCESS.equals(jwt.getClaimAsString(AuthClaims.TYPE))) {
            throw new InvalidBearerTokenException(
                    "Token type is not valid for API access");
        }

        Set<String> roles = Set.copyOf(claimList(jwt, AuthClaims.ROLES));
        Set<String> permissions = Set.copyOf(claimList(jwt, AuthClaims.PERMISSIONS));

        Set<GrantedAuthority> authorities = new LinkedHashSet<>();
        permissions.forEach(permission -> authorities.add(new SimpleGrantedAuthority(permission)));
        roles.forEach(role -> authorities.add(new SimpleGrantedAuthority("ROLE_" + role)));

        AuthenticatedPrincipal principal = new AuthenticatedPrincipal(
                UUID.fromString(jwt.getSubject()),
                jwt.getClaimAsString(AuthClaims.USERNAME),
                jwt.getClaimAsString(AuthClaims.EMAIL),
                jwt.getClaimAsString(AuthClaims.FULL_NAME),
                uuidClaim(jwt, AuthClaims.ORGANIZATION_ID),
                jwt.getClaimAsString(AuthClaims.ORGANIZATION_CODE),
                roles,
                permissions,
                uuidClaim(jwt, AuthClaims.SESSION_ID),
                Boolean.TRUE.equals(jwt.getClaim(AuthClaims.MUST_CHANGE_PASSWORD)));

        return new PrincipalAuthenticationToken(jwt, principal, authorities);
    }

    private List<String> claimList(Jwt jwt, String claim) {
        List<String> values = jwt.getClaimAsStringList(claim);
        return values == null ? List.of() : values;
    }

    private UUID uuidClaim(Jwt jwt, String claim) {
        String value = jwt.getClaimAsString(claim);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new InvalidBearerTokenException("Claim '%s' is not a valid identifier".formatted(claim));
        }
    }

    /** Carries the platform principal while remaining a standard {@link JwtAuthenticationToken}. */
    public static final class PrincipalAuthenticationToken extends JwtAuthenticationToken {

        private final transient AuthenticatedPrincipal principal;

        PrincipalAuthenticationToken(Jwt jwt,
                                     AuthenticatedPrincipal principal,
                                     java.util.Collection<? extends GrantedAuthority> authorities) {
            super(jwt, authorities, principal.username());
            this.principal = principal;
        }

        @Override
        public Object getPrincipal() {
            return principal;
        }
    }
}
