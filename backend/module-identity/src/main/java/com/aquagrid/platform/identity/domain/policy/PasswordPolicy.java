package com.aquagrid.platform.identity.domain.policy;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Password strength rules, as a pure domain object.
 *
 * <p>No Spring, no persistence, no I/O — so the entire rule matrix is unit-testable in
 * milliseconds, and so the policy can become per-tenant configuration in Module 3 by constructing
 * a different instance rather than by editing branching logic.
 *
 * <p>The design follows NIST SP 800-63B where it conflicts with folklore:
 * <ul>
 *   <li><b>Length is weighted above composition.</b> A 16-character passphrase beats
 *       {@code P@ssw0rd!} by orders of magnitude, and composition rules mostly teach users to
 *       append {@code 1!}.</li>
 *   <li><b>Context terms are blocked</b> — username, email local part, organisation name and
 *       product name. These are the first candidates in any targeted attack.</li>
 *   <li><b>No forced periodic expiry by default.</b> Mandatory 90-day rotation demonstrably
 *       produces weaker, incrementing passwords. {@code maxAgeDays} exists because public-sector
 *       procurement sometimes mandates it, and defaults to disabled.</li>
 * </ul>
 */
public record PasswordPolicy(
        int minLength,
        int maxLength,
        boolean requireUppercase,
        boolean requireLowercase,
        boolean requireDigit,
        boolean requireSpecial,
        int historyDepth,
        int maxAgeDays,
        Set<String> blockedPasswords
) {

    private static final Set<String> DEFAULT_BLOCKLIST = Set.of(
            "password", "passw0rd", "password1", "123456", "12345678", "123456789", "qwerty",
            "abc123", "letmein", "welcome", "admin", "administrator", "iloveyou", "monkey",
            "dragon", "sunshine", "princess", "football", "aquagrid", "water", "watermeter",
            "changeme", "secret", "default", "test1234", "qwerty123", "1q2w3e4r");

    public static PasswordPolicy defaultPolicy() {
        return new PasswordPolicy(12, 128, true, true, true, true, 5, 0, DEFAULT_BLOCKLIST);
    }

    public PasswordPolicy {
        blockedPasswords = blockedPasswords == null ? DEFAULT_BLOCKLIST : Set.copyOf(blockedPasswords);
        if (minLength < 8) {
            throw new IllegalArgumentException("Minimum password length must be at least 8");
        }
        if (maxLength < minLength) {
            throw new IllegalArgumentException("Maximum password length must not be below the minimum");
        }
    }

    /**
     * Validates a candidate password.
     *
     * @param contextTerms values the password must not contain (username, email, organisation…)
     * @return every violation found, in display order; empty when the password is acceptable
     */
    public List<String> validate(String password, String... contextTerms) {
        List<String> violations = new ArrayList<>();
        if (password == null || password.isEmpty()) {
            violations.add("Password is required");
            return violations;
        }
        if (password.length() < minLength) {
            violations.add("Must be at least %d characters long".formatted(minLength));
        }
        if (password.length() > maxLength) {
            violations.add("Must be at most %d characters long".formatted(maxLength));
        }
        if (requireUppercase && password.chars().noneMatch(Character::isUpperCase)) {
            violations.add("Must contain an uppercase letter");
        }
        if (requireLowercase && password.chars().noneMatch(Character::isLowerCase)) {
            violations.add("Must contain a lowercase letter");
        }
        if (requireDigit && password.chars().noneMatch(Character::isDigit)) {
            violations.add("Must contain a digit");
        }
        if (requireSpecial && password.chars().allMatch(Character::isLetterOrDigit)) {
            violations.add("Must contain a special character");
        }

        String normalised = password.toLowerCase(Locale.ROOT);
        if (blockedPasswords.contains(normalised)) {
            violations.add("This password is too common");
        }
        if (hasRun(password, 4)) {
            violations.add("Must not contain 4 or more repeated or sequential characters");
        }
        // Compared with separators stripped from both sides: an organisation code of KWA-TVM must
        // not be defeated by typing KwaTvm, and an email local part of j.mathew must not be
        // defeated by typing Jmathew. A literal comparison catches neither.
        String alphanumeric = stripSeparators(normalised);
        for (String term : contextTerms) {
            if (term == null) {
                continue;
            }
            String candidate = term.toLowerCase(Locale.ROOT);
            int at = candidate.indexOf('@');
            if (at > 2) {
                candidate = candidate.substring(0, at);
            }
            candidate = stripSeparators(candidate);
            if (candidate.length() < 3) {
                continue;
            }
            if (normalised.contains(candidate) || alphanumeric.contains(candidate)) {
                violations.add("Must not contain your name, username, email or organisation");
                break;
            }
        }
        return violations;
    }

    /** Reduces a value to its letters and digits, so punctuation cannot mask a context term. */
    private static String stripSeparators(String value) {
        return value.replaceAll("[^a-z0-9]", "");
    }

    /** Detects {@code aaaa}, {@code 1234} and {@code dcba} style runs of the given length. */
    private boolean hasRun(String password, int runLength) {
        if (password.length() < runLength) {
            return false;
        }
        int repeated = 1;
        int ascending = 1;
        int descending = 1;
        for (int i = 1; i < password.length(); i++) {
            int delta = password.charAt(i) - password.charAt(i - 1);
            repeated = delta == 0 ? repeated + 1 : 1;
            ascending = delta == 1 ? ascending + 1 : 1;
            descending = delta == -1 ? descending + 1 : 1;
            if (repeated >= runLength || ascending >= runLength || descending >= runLength) {
                return true;
            }
        }
        return false;
    }

    /** Human-readable summary, returned by {@code GET /auth/password/policy} for live UI hints. */
    public List<String> describe() {
        List<String> rules = new ArrayList<>();
        rules.add("At least %d characters".formatted(minLength));
        if (requireUppercase) {
            rules.add("At least one uppercase letter");
        }
        if (requireLowercase) {
            rules.add("At least one lowercase letter");
        }
        if (requireDigit) {
            rules.add("At least one digit");
        }
        if (requireSpecial) {
            rules.add("At least one special character");
        }
        rules.add("Must not repeat your last %d passwords".formatted(historyDepth));
        rules.add("Must not contain your name, username or email");
        return rules;
    }
}
