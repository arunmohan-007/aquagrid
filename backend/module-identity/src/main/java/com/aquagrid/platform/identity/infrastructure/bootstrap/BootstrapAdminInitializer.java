package com.aquagrid.platform.identity.infrastructure.bootstrap;

import com.aquagrid.platform.common.audit.AuditCategory;
import com.aquagrid.platform.common.audit.AuditEvent;
import com.aquagrid.platform.common.audit.AuditEventTypes;
import com.aquagrid.platform.common.audit.AuditService;
import com.aquagrid.platform.common.audit.AuditSeverity;
import com.aquagrid.platform.identity.domain.enums.UserStatus;
import com.aquagrid.platform.identity.domain.model.Organization;
import com.aquagrid.platform.identity.domain.model.Role;
import com.aquagrid.platform.identity.domain.model.User;
import com.aquagrid.platform.identity.domain.policy.PasswordPolicy;
import com.aquagrid.platform.identity.infrastructure.config.IdentityProperties;
import com.aquagrid.platform.identity.infrastructure.persistence.OrganizationRepository;
import com.aquagrid.platform.identity.infrastructure.persistence.RoleRepository;
import com.aquagrid.platform.identity.infrastructure.persistence.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Creates the first administrator on an empty installation.
 *
 * <p>This exists instead of seeding a user in a migration, and the difference matters. A migration
 * can only contain a <em>fixed</em> password hash, which means every installation of the product
 * ships with the same known credential — the single most reliably exploited weakness in on-premise
 * enterprise software. Here the password comes from the environment, is never written to a file in
 * the repository, and the account is created with {@code mustChangePassword} set.
 *
 * <p>The runner is idempotent and conservative: it does nothing at all if any user already exists,
 * so it can never resurrect a deliberately deleted administrator or overwrite a real one.
 */
@Slf4j
@Component
@Order(100)
@RequiredArgsConstructor
public class BootstrapAdminInitializer implements ApplicationRunner {

    private static final String SUPER_ADMIN_ROLE = "SUPER_ADMIN";

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final OrganizationRepository organizationRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;
    private final IdentityProperties properties;
    private final Clock clock;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (userRepository.countAllUsers() > 0) {
            log.debug("Users already exist; skipping bootstrap administrator creation");
            return;
        }

        IdentityProperties.Bootstrap config = properties.bootstrap();
        if (!config.isEnabled()) {
            log.warn("""
                    ================================================================
                     No users exist and AQUAGRID_BOOTSTRAP_ADMIN_PASSWORD is not set,
                     so no administrator was created and nobody can sign in.
                     Set that variable and restart to create '{}'.
                    ================================================================""",
                    config.username());
            return;
        }

        PasswordPolicy policy = new PasswordPolicy(properties.password().minLength(),
                properties.password().maxLength(), properties.password().requireUppercase(),
                properties.password().requireLowercase(), properties.password().requireDigit(),
                properties.password().requireSpecial(), properties.password().historyDepth(),
                properties.password().maxAgeDays(), null);
        List<String> violations = policy.validate(config.password(), config.username(),
                config.email(), config.fullName());
        if (!violations.isEmpty()) {
            // Refuse rather than weaken the policy for the most privileged account in the system.
            throw new IllegalStateException(
                    "The bootstrap administrator password does not meet the password policy: "
                            + String.join("; ", violations));
        }

        Organization organization = organizationRepository
                .findByCodeIgnoreCase(config.organizationCode())
                .orElseThrow(() -> new IllegalStateException(
                        "Bootstrap organisation '%s' does not exist".formatted(config.organizationCode())));

        Role superAdmin = roleRepository.findByCodeAndOrganizationIdIsNull(SUPER_ADMIN_ROLE)
                .orElseThrow(() -> new IllegalStateException(
                        "System role %s is missing; migrations may not have run"
                                .formatted(SUPER_ADMIN_ROLE)));

        Instant now = clock.instant();
        User admin = new User();
        admin.setOrganization(organization);
        admin.setUsername(config.username());
        admin.setEmail(config.email());
        admin.setFullName(config.fullName());
        admin.setPasswordHash(passwordEncoder.encode(config.password()));
        admin.setPasswordUpdatedAt(now);
        admin.setStatus(UserStatus.ACTIVE);
        // The environment variable is visible in process listings, deployment manifests and shell
        // history, so this password is treated as compromised from birth.
        admin.setMustChangePassword(true);
        admin.setRoles(Set.of(superAdmin));

        userRepository.save(admin);

        auditService.record(AuditEvent.builder()
                .organizationId(organization.getId())
                .actorUsername("system")
                .eventType(AuditEventTypes.BOOTSTRAP_ADMIN_CREATED)
                .category(AuditCategory.SYSTEM)
                .severity(AuditSeverity.WARNING)
                .resourceType("User")
                .resourceId(admin.getId().toString())
                .success(true)
                .message("Bootstrap administrator '%s' created on an empty installation"
                        .formatted(config.username()))
                .build());

        log.info("""
                ================================================================
                 Bootstrap administrator created
                   organisation : {}
                   username     : {}
                   email        : {}
                 The password must be changed at first sign-in.
                ================================================================""",
                organization.getCode(), admin.getUsername(), admin.getEmail());
    }
}
