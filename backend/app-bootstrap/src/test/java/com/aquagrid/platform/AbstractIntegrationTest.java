package com.aquagrid.platform;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Base class for integration tests.
 *
 * <p>Runs against a real PostGIS container, never an in-memory database. H2 does not implement
 * PostGIS, {@code citext}, partial unique indexes, {@code inet}, {@code jsonb} or the triggers this
 * schema relies on — a green H2 test suite would prove nothing about whether the product works.
 *
 * <p>The container is static, so one instance is reused across every test class in the run rather
 * than paying container startup per class.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class AbstractIntegrationTest {

    // pgrouting/pgrouting, not postgis/postgis: V1320 does CREATE EXTENSION pgrouting, which the
    // plain PostGIS image does not ship. This image bundles PostgreSQL 16 + PostGIS + pgRouting.
    // Note it carries PostGIS 3.5 while deploy/postgres builds on 3.4 — worth converging so tests
    // and production run the same spatial version.
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgrouting/pgrouting:16-3.5-3.7.3")
                    .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("aquagrid_test")
            .withUsername("aquagrid")
            .withPassword("aquagrid")
            // Must match production: the entities bind Strings into inet columns, which the
            // driver only accepts when string parameters are sent untyped.
            .withUrlParam("stringtype", "unspecified")
            .withReuse(true);

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}
