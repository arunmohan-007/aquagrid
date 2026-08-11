package com.aquagrid.platform.common.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

import java.util.Optional;
import java.util.UUID;

@Configuration
@EnableJpaAuditing(auditorAwareRef = "auditorProvider")
public class JpaConfig {

    /**
     * Feeds {@code @CreatedBy}/{@code @LastModifiedBy}.
     *
     * <p>Resolved lazily through {@link ObjectProvider} so the kernel starts even when no security
     * module is on the classpath — the case for standalone batch and test contexts, where rows are
     * simply attributed to no actor.
     */
    @Bean
    public AuditorAware<UUID> auditorProvider(ObjectProvider<CurrentActorProvider> actorProvider) {
        return () -> Optional.ofNullable(actorProvider.getIfAvailable())
                .flatMap(CurrentActorProvider::currentUserId);
    }
}
