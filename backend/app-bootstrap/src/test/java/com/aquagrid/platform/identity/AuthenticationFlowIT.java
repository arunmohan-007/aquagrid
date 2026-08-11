package com.aquagrid.platform.identity;

import com.aquagrid.platform.AbstractIntegrationTest;
import com.aquagrid.platform.common.crypto.CryptoService;
import com.aquagrid.platform.common.crypto.TotpService;
import com.aquagrid.platform.identity.domain.enums.LoginOutcome;
import com.aquagrid.platform.identity.domain.enums.UserStatus;
import com.aquagrid.platform.identity.domain.model.Organization;
import com.aquagrid.platform.identity.domain.model.Role;
import com.aquagrid.platform.identity.domain.model.User;
import com.aquagrid.platform.identity.infrastructure.persistence.LoginAttemptRepository;
import com.aquagrid.platform.identity.infrastructure.persistence.OrganizationRepository;
import com.aquagrid.platform.identity.infrastructure.persistence.RoleRepository;
import com.aquagrid.platform.identity.infrastructure.persistence.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end verification of the Module 1 authentication surface against real PostGIS.
 *
 * <p>These are the behaviours whose failure would be a security incident rather than a bug, so they
 * are asserted explicitly: refresh-token rotation, reuse detection with family revocation, lockout,
 * and the guarantee that an unknown account and a wrong password are indistinguishable to a client.
 */
@AutoConfigureMockMvc
class AuthenticationFlowIT extends AbstractIntegrationTest {

    private static final String PASSWORD = "Trivandrum#Water47";
    private static final String EMAIL = "j.mathew@kwa.test";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private RoleRepository roleRepository;
    @Autowired
    private OrganizationRepository organizationRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private LoginAttemptRepository loginAttemptRepository;
    @Autowired
    private TotpService totpService;
    @Autowired
    private CryptoService cryptoService;

    @BeforeEach
    @Transactional
    void createUser() {
        if (userRepository.findByEmailIgnoreCase(EMAIL).isPresent()) {
            return;
        }
        Organization organization = organizationRepository.findByCodeIgnoreCase("SYSTEM")
                .orElseThrow();
        Role viewer = roleRepository.findByCodeAndOrganizationIdIsNull("VIEWER").orElseThrow();

        User user = new User();
        user.setOrganization(organization);
        user.setUsername("j.mathew");
        user.setEmail(EMAIL);
        user.setFullName("Jacob Mathew");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setPasswordUpdatedAt(Instant.now());
        user.setStatus(UserStatus.ACTIVE);
        user.setRoles(Set.of(viewer));
        userRepository.save(user);
    }

    @Test
    @DisplayName("signs in, returns an access token, and sets an HttpOnly refresh cookie")
    void signsInSuccessfully() throws Exception {
        MvcResult result = login(EMAIL, PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.mfaRequired").value(false))
                .andExpect(jsonPath("$.user.email").value(EMAIL))
                .andExpect(jsonPath("$.user.permissions").isArray())
                // The tenant's map bootstrap payload travels with the principal, so the GIS
                // dashboard opens on the right extent without a second round trip.
                .andExpect(jsonPath("$.user.organization.defaultCenter").isArray())
                .andExpect(cookie().exists("ag_rt"))
                .andExpect(cookie().httpOnly("ag_rt", true))
                .andReturn();

        // The refresh token must never appear in the response body.
        assertThat(result.getResponse().getContentAsString()).doesNotContain("ag_rt");
    }

    @Test
    @DisplayName("an unknown account and a wrong password are indistinguishable")
    void doesNotLeakAccountExistence() throws Exception {
        String unknownAccount = login("nobody@kwa.test", PASSWORD)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_INVALID_CREDENTIALS"))
                .andReturn().getResponse().getContentAsString();

        String wrongPassword = login(EMAIL, "Completely#Wrong99")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_INVALID_CREDENTIALS"))
                .andReturn().getResponse().getContentAsString();

        assertThat(field(unknownAccount, "detail")).isEqualTo(field(wrongPassword, "detail"));
        assertThat(field(unknownAccount, "code")).isEqualTo(field(wrongPassword, "code"));
    }

    @Test
    @DisplayName("rotates the refresh token and revokes the whole family when one is reused")
    void detectsRefreshTokenReuse() throws Exception {
        String originalCookie = login(EMAIL, PASSWORD)
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie("ag_rt").getValue();

        // First rotation succeeds and issues a different token.
        String rotatedCookie = mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new jakarta.servlet.http.Cookie("ag_rt", originalCookie)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andReturn().getResponse().getCookie("ag_rt").getValue();

        assertThat(rotatedCookie).isNotEqualTo(originalCookie);

        // Replaying the original is only possible if it leaked: the family is revoked.
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new jakarta.servlet.http.Cookie("ag_rt", originalCookie)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REFRESH_TOKEN_REUSED"));

        // ...and the successor the legitimate client holds is dead too.
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new jakarta.servlet.http.Cookie("ag_rt", rotatedCookie)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("locks the account after the configured number of failures")
    void locksAccountAfterRepeatedFailures() throws Exception {
        String email = "lockout.target@kwa.test";
        createSecondaryUser(email);

        for (int attempt = 0; attempt < 3; attempt++) {
            login(email, "Completely#Wrong99").andExpect(status().isUnauthorized());
        }

        // Even the correct password is now refused, and the response says for how long.
        login(email, PASSWORD)
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.code").value("AUTH_ACCOUNT_LOCKED"))
                .andExpect(jsonPath("$.retryAfterSeconds").isNumber());
    }

    /*
     * The two assertions below are the same guarantee stated at the level it actually broke.
     *
     * `locksAccountAfterRepeatedFailures` catches a rolled-back counter only on the fourth request,
     * which reads as a lockout-policy test; the underlying defect was that a refused login committed
     * nothing at all, because BusinessException is a RuntimeException and Spring's default rollback
     * rule discarded every write the failure path had just made. The forensic row had no coverage of
     * its own, so the loss of the entire login_attempts trail was invisible.
     */
    @Test
    @DisplayName("a refused sign-in still persists the counter and the forensic record")
    void failedLoginCommitsItsEvidence() throws Exception {
        String email = "evidence.target@kwa.test";
        createSecondaryUser(email, "evidence.target");

        login(email, "Completely#Wrong99").andExpect(status().isUnauthorized());

        UUID userId = userRepository.findByEmailIgnoreCase(email).orElseThrow().getId();

        // The counter that lockout is computed from survived the exception that reported the refusal.
        assertThat(userRepository.findById(userId).orElseThrow().getFailedLoginAttempts())
                .isEqualTo(1);

        // ...and so did the row a security review reads.
        assertThat(loginAttemptRepository.findRecentForUser(userId, PageRequest.of(0, 10)))
                .isNotEmpty()
                .anySatisfy(attempt ->
                        assertThat(attempt.getOutcome()).isEqualTo(LoginOutcome.INVALID_CREDENTIALS));
    }

    /**
     * The second factor has to lock out too, and for a stronger reason than the password does.
     *
     * <p>A TOTP code is six digits: without a working counter, the challenge endpoint is a 10^6
     * keyspace protected by nothing but a rate limiter. This path had the same rollback defect as
     * {@code login} — {@code registerFailedLogin} incremented, {@code MFA_CODE_INVALID} was thrown,
     * and the increment went with it — and no test covered it, which is why it survived.
     */
    @Test
    @DisplayName("repeated wrong verification codes lock the account")
    void locksAccountAfterRepeatedMfaFailures() throws Exception {
        String email = "mfa.target@kwa.test";
        String secret = createMfaUser(email);

        String mfaToken = field(login(email, PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mfaRequired").value(true))
                .andReturn().getResponse().getContentAsString(), "mfaToken");

        // A code that is definitely not the live one, rather than a guess that could coincide with it.
        String liveCode = totpService.currentCode(secret);
        String wrongCode = liveCode.equals("000000") ? "111111" : "000000";

        for (int attempt = 0; attempt < 3; attempt++) {
            challenge(mfaToken, wrongCode).andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("MFA_CODE_INVALID"));
        }

        // The counter survived all three refusals...
        UUID userId = userRepository.findByEmailIgnoreCase(email).orElseThrow().getId();
        assertThat(userRepository.findById(userId).orElseThrow().getFailedLoginAttempts())
                .isEqualTo(3);

        // ...so the fourth attempt is refused before the code is even examined.
        challenge(mfaToken, liveCode)
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.code").value("AUTH_ACCOUNT_LOCKED"));
    }

    @Test
    @DisplayName("rejects /me without a token and accepts it with one")
    void protectsAuthenticatedEndpoints() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));

        String accessToken = field(login(EMAIL, PASSWORD).andReturn().getResponse()
                .getContentAsString(), "accessToken");

        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(EMAIL))
                .andExpect(jsonPath("$.roles[0]").value("VIEWER"));
    }

    @Test
    @DisplayName("never confirms whether an address is registered on password reset")
    void forgotPasswordDoesNotEnumerate() throws Exception {
        String known = mockMvc.perform(post("/api/v1/auth/password/forgot")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + EMAIL + "\"}"))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();

        String unknown = mockMvc.perform(post("/api/v1/auth/password/forgot")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"nobody@kwa.test\"}"))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();

        assertThat(known).isEqualTo(unknown);
    }

    @Test
    @DisplayName("publishes a JWKS document so extracted services can validate tokens offline")
    void publishesJwks() throws Exception {
        mockMvc.perform(get("/.well-known/jwks.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keys[0].kty").value("RSA"))
                .andExpect(jsonPath("$.keys[0].alg").value("RS256"))
                .andExpect(jsonPath("$.keys[0].kid").isNotEmpty())
                // A private exponent here would be a catastrophic disclosure.
                .andExpect(jsonPath("$.keys[0].d").doesNotExist());
    }

    // --- helpers ------------------------------------------------------------------------------

    private org.springframework.test.web.servlet.ResultActions login(String identifier,
                                                                     String password)
            throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"identifier":"%s","password":"%s"}""".formatted(identifier, password)));
    }

    private org.springframework.test.web.servlet.ResultActions challenge(String mfaToken,
                                                                         String code)
            throws Exception {
        return mockMvc.perform(post("/api/v1/auth/mfa/challenge")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"mfaToken":"%s","code":"%s"}""".formatted(mfaToken, code)));
    }

    @Transactional
    void createSecondaryUser(String email) {
        createSecondaryUser(email, "lockout.target");
    }

    /**
     * Creates an MFA-enrolled user and returns the plaintext TOTP secret.
     *
     * <p>Enrolled directly rather than through {@code /mfa/setup} + {@code /mfa/activate}: this test
     * is about what happens to the failure counter, and driving the real enrolment flow would make
     * it fail for reasons that have nothing to do with that.
     */
    @Transactional
    String createMfaUser(String email) {
        String secret = totpService.generateSecret();
        userRepository.findByEmailIgnoreCase(email).ifPresentOrElse(
                existing -> {
                    existing.setMfaSecret(cryptoService.encrypt(secret));
                    existing.setMfaEnabled(true);
                    userRepository.save(existing);
                },
                () -> {
                    createSecondaryUser(email, "mfa.target");
                    User user = userRepository.findByEmailIgnoreCase(email).orElseThrow();
                    user.setMfaSecret(cryptoService.encrypt(secret));
                    user.setMfaEnabled(true);
                    user.setMfaConfirmedAt(Instant.now());
                    userRepository.save(user);
                });
        return secret;
    }

    /** Username is unique per tenant, so a second fixture user needs its own. */
    @Transactional
    void createSecondaryUser(String email, String username) {
        if (userRepository.findByEmailIgnoreCase(email).isPresent()) {
            return;
        }
        Organization organization = organizationRepository.findByCodeIgnoreCase("SYSTEM").orElseThrow();
        Role viewer = roleRepository.findByCodeAndOrganizationIdIsNull("VIEWER").orElseThrow();

        User user = new User();
        user.setOrganization(organization);
        user.setUsername(username);
        user.setEmail(email);
        user.setFullName("Lockout Target");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setPasswordUpdatedAt(Instant.now());
        user.setStatus(UserStatus.ACTIVE);
        user.setRoles(Set.of(viewer));
        userRepository.save(user);
    }

    private String field(String json, String name) throws Exception {
        JsonNode node = objectMapper.readTree(json).get(name);
        return node == null ? null : node.asText();
    }
}
