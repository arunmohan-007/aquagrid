package com.aquagrid.platform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * The AquaGrid platform application.
 *
 * <p>This is the only executable in the build. Every business capability lives in a sibling Maven
 * module that this one depends on; the modules never depend on it. Component scanning is rooted at
 * {@code com.aquagrid.platform}, so adding a module to the POM is all that is required to activate
 * it — and removing one genuinely removes it, which is the property that makes extraction into a
 * separate service a deployment change rather than a rewrite.
 */
@SpringBootApplication(scanBasePackages = "com.aquagrid.platform")
@EntityScan(basePackages = "com.aquagrid.platform")
@EnableJpaRepositories(basePackages = "com.aquagrid.platform")
public class AquaGridApplication {

    public static void main(String[] args) {
        SpringApplication.run(AquaGridApplication.class, args);
    }
}
