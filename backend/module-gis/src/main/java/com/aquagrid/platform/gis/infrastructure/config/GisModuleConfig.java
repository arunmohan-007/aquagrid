package com.aquagrid.platform.gis.infrastructure.config;

import com.aquagrid.platform.gis.storage.FilesystemObjectStorage;
import com.aquagrid.platform.gis.storage.ObjectStoragePort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * GIS module wiring.
 *
 * <p>Mostly thin: the module's beans are discovered by component scan (same base package as the
 * rest of the platform). The one exception is object storage, whose default has to be declared as
 * a {@code @Bean} so {@code @ConditionalOnMissingBean} is actually evaluated.
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(GisTileProperties.class)
public class GisModuleConfig {

    public GisModuleConfig(GisTileProperties tiles) {
        log.info("GIS module configured: vector tiles via ST_AsMVT (extent {}, buffer {}), "
                        + "MapLibre GL client",
                tiles.extent(), tiles.buffer());
    }

    /**
     * The attachment store used when nothing else supplies one.
     *
     * <p>{@code @ConditionalOnMissingBean} works here and would not on the implementation class:
     * on a component-scanned {@code @Component} the condition is evaluated against a partially
     * populated bean registry, and the bean silently fails to register — which is exactly what
     * happened before this moved.
     */
    @Bean
    @ConditionalOnMissingBean(ObjectStoragePort.class)
    public ObjectStoragePort filesystemObjectStorage(
            @Value("${aquagrid.storage.filesystem.root:./data/attachments}") String root) {
        return new FilesystemObjectStorage(root);
    }
}
