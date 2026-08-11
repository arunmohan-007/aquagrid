package com.aquagrid.platform.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * OpenAPI 3.1 document metadata.
 *
 * <p>The specification is generated from the actual controllers and their Bean Validation
 * constraints, so it cannot drift from the implementation. It is a build artefact: CI publishes it,
 * generates the typed frontend client from it, and diffs it against the previous release to detect
 * breaking changes before they ship.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI aquaGridOpenApi(@Value("${aquagrid.openapi.server-url:/}") String serverUrl) {
        return new OpenAPI()
                .info(new Info()
                        .title("AquaGrid Enterprise Water Management Platform API")
                        .version("v1")
                        .description("""
                                REST API for the AquaGrid platform.

                                **Authentication.** Call `POST /api/v1/auth/login`, then send the
                                returned access token as `Authorization: Bearer <token>`. The
                                refresh token is delivered as an `HttpOnly` cookie and is never
                                exposed to JavaScript; call `POST /api/v1/auth/refresh` to rotate it.

                                **Errors.** Every failure is an RFC 7807 `application/problem+json`
                                document with a stable `code` and a `traceId`. Branch on `code`,
                                never on `detail`.

                                **Authorisation.** Permissions are `domain:resource:action` codes
                                carried in the access token.""")
                        .contact(new Contact().name("AquaGrid Platform Team")
                                .email("support@aquagrid.com"))
                        .license(new License().name("Commercial")))
                .servers(List.of(new Server().url(serverUrl).description("Current deployment")))
                .components(new Components().addSecuritySchemes("bearerAuth", new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description("Access token from POST /api/v1/auth/login")));
    }
}
