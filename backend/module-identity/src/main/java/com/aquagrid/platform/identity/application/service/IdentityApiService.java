package com.aquagrid.platform.identity.application.service;

import com.aquagrid.platform.identity.api.IdentityApi;
import com.aquagrid.platform.identity.api.TenantSummary;
import com.aquagrid.platform.identity.api.UserSummary;
import com.aquagrid.platform.identity.application.mapper.UserMapper;
import com.aquagrid.platform.identity.domain.enums.UserStatus;
import com.aquagrid.platform.identity.infrastructure.persistence.OrganizationRepository;
import com.aquagrid.platform.identity.infrastructure.persistence.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

import static com.aquagrid.platform.common.config.CacheConfig.USER_PERMISSIONS;

/** In-process implementation of the published {@link IdentityApi}. */
@Service
@RequiredArgsConstructor
public class IdentityApiService implements IdentityApi {

    private final UserRepository userRepository;
    private final OrganizationRepository organizationRepository;
    private final UserMapper userMapper;

    @Override
    @Transactional(readOnly = true)
    public Optional<UserSummary> findUser(UUID userId) {
        return userRepository.findByIdWithAuthorities(userId)
                .map(user -> new UserSummary(
                        user.getId(),
                        user.getOrganization().getId(),
                        user.getUsername(),
                        user.getEmail(),
                        user.getFullName(),
                        user.getJobTitle(),
                        user.getAvatarUrl(),
                        user.getStatus() == UserStatus.ACTIVE));
    }

    /**
     * Server-side permission check, for the rare case where a decision cannot rely on the token —
     * background jobs, message consumers, and re-verification of a long-running operation.
     *
     * <p>Cached for five minutes (see {@code CacheConfig}), which bounds how long a revoked
     * permission can still be honoured. Request-path authorisation does not come through here: it
     * reads the token claims and is exact.
     */
    @Override
    @Transactional(readOnly = true)
    @Cacheable(cacheNames = USER_PERMISSIONS, key = "#userId + ':' + #permissionCode")
    public boolean hasPermission(UUID userId, String permissionCode) {
        return userRepository.findByIdWithAuthorities(userId)
                .filter(user -> user.getStatus() == UserStatus.ACTIVE)
                .map(user -> userMapper.permissionCodes(user).contains(permissionCode))
                .orElse(false);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TenantSummary> findTenantByCode(String code) {
        return organizationRepository.findByCodeIgnoreCase(code)
                .map(org -> new TenantSummary(org.getId(), org.getCode(), org.getName(),
                        org.getTimezone(), org.getLocale()));
    }
}
