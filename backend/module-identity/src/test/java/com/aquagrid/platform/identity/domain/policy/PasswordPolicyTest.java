package com.aquagrid.platform.identity.domain.policy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PasswordPolicyTest {

    private final PasswordPolicy policy = PasswordPolicy.defaultPolicy();

    @Test
    @DisplayName("accepts a password that satisfies every rule")
    void acceptsStrongPassword() {
        assertThat(policy.validate("Trivandrum#Water47")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Sh0rt#a", "Ab1#defg"})
    @DisplayName("rejects passwords below the minimum length")
    void rejectsShortPasswords(String candidate) {
        assertThat(policy.validate(candidate))
                .anyMatch(violation -> violation.contains("at least 12 characters"));
    }

    @Test
    @DisplayName("enforces each composition rule independently")
    void enforcesCompositionRules() {
        assertThat(policy.validate("trivandrum#water47"))
                .contains("Must contain an uppercase letter");
        assertThat(policy.validate("TRIVANDRUM#WATER47"))
                .contains("Must contain a lowercase letter");
        assertThat(policy.validate("TrivandrumWater#"))
                .contains("Must contain a digit");
        assertThat(policy.validate("TrivandrumWater47"))
                .contains("Must contain a special character");
    }

    @Test
    @DisplayName("rejects common passwords outright")
    void rejectsCommonPasswords() {
        assertThat(policy.validate("password")).contains("This password is too common");
        assertThat(policy.validate("ChangeMe".toLowerCase()))
                .contains("This password is too common");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Waterrrr#2026", "Water1234#Ab", "Waterdcba#12"})
    @DisplayName("rejects repeated and sequential runs")
    void rejectsRuns(String candidate) {
        assertThat(policy.validate(candidate))
                .anyMatch(violation -> violation.contains("repeated or sequential"));
    }

    @Test
    @DisplayName("rejects a password containing the user's own identifiers")
    void rejectsContextTerms() {
        assertThat(policy.validate("Jmathew#Secure99", "j.mathew@kwa.gov.in"))
                .anyMatch(violation -> violation.contains("Must not contain your name"));

        assertThat(policy.validate("KwaTvm#Secure99", "j.mathew", "KWA-TVM"))
                .anyMatch(violation -> violation.contains("Must not contain your name"));
    }

    @Test
    @DisplayName("ignores context terms that are too short to be meaningful")
    void ignoresShortContextTerms() {
        assertThat(policy.validate("Trivandrum#Water47", "ab", "x")).isEmpty();
    }

    @Test
    @DisplayName("reports every violation at once so the UI can show a complete checklist")
    void reportsAllViolations() {
        assertThat(policy.validate("abc")).hasSizeGreaterThanOrEqualTo(4);
    }

    @Test
    @DisplayName("treats a null or empty password as a single, clear failure")
    void handlesMissingPassword() {
        assertThat(policy.validate(null)).containsExactly("Password is required");
        assertThat(policy.validate("")).containsExactly("Password is required");
    }

    @Test
    @DisplayName("refuses to construct a policy weaker than 8 characters")
    void refusesWeakPolicy() {
        assertThatThrownBy(() -> new PasswordPolicy(6, 128, true, true, true, true, 5, 0, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 8");
    }

    @Test
    @DisplayName("describes itself for the client-side hint list")
    void describesItself() {
        assertThat(policy.describe())
                .contains("At least 12 characters")
                .contains("At least one uppercase letter")
                .contains("Must not repeat your last 5 passwords");
    }
}
