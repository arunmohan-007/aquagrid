package com.aquagrid.platform.security.core;

/**
 * Names of the custom JWT claims.
 *
 * <p>Short names are used deliberately: every claim is repeated on every request of every user, and
 * at fleet scale the header bytes are real. Registered claims ({@code iss}, {@code sub},
 * {@code aud}, {@code exp}, {@code iat}, {@code jti}) keep their standard names.
 */
public final class AuthClaims {

    /** Token purpose — {@link #TYPE_ACCESS} or {@link #TYPE_MFA}. */
    public static final String TYPE = "typ";

    public static final String TYPE_ACCESS = "access";
    public static final String TYPE_MFA = "mfa";

    /** Organization (tenant) id. */
    public static final String ORGANIZATION_ID = "org";
    /** Organization code, so the UI can render the tenant without a lookup. */
    public static final String ORGANIZATION_CODE = "oc";
    public static final String USERNAME = "usr";
    public static final String EMAIL = "eml";
    public static final String FULL_NAME = "nam";
    /** Role codes, without the {@code ROLE_} prefix. */
    public static final String ROLES = "rol";
    /** Permission codes, e.g. {@code gis:pipeline:update}. */
    public static final String PERMISSIONS = "prm";
    /** Refresh-token family id, linking an access token to the session that issued it. */
    public static final String SESSION_ID = "sid";
    /** True when the user must change their password before using the API. */
    public static final String MUST_CHANGE_PASSWORD = "mcp";

    private AuthClaims() {
    }
}
