# =====================================================================================
# AquaGrid platform image
#
# Multi-stage with an explicit dependency-cache layer, and a layered Spring Boot runtime
# so that an application-code change rebuilds only the last, smallest layer.
# Runs as a non-root user on a JRE-only base with no shell package manager.
# =====================================================================================

# ---- Stage 1: dependency cache -------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS deps
WORKDIR /build
COPY pom.xml .
COPY platform-common/pom.xml platform-common/
COPY platform-security/pom.xml platform-security/
COPY module-identity/pom.xml module-identity/
COPY module-gis/pom.xml module-gis/
COPY module-iot/pom.xml module-iot/
COPY app-bootstrap/pom.xml app-bootstrap/
# Resolves the full dependency tree with no source present, so this layer is invalidated
# only by a POM change — not by every edit to a Java file.
RUN mvn -B -q dependency:go-offline -DskipTests

# ---- Stage 2: build ------------------------------------------------------------------
FROM deps AS build
COPY platform-common/src platform-common/src
COPY platform-security/src platform-security/src
COPY module-identity/src module-identity/src
COPY module-gis/src module-gis/src
COPY module-iot/src module-iot/src
COPY app-bootstrap/src app-bootstrap/src
RUN mvn -B -q clean package -DskipTests

# ---- Stage 3: layer extraction -------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine AS extract
WORKDIR /extract
COPY --from=build /build/app-bootstrap/target/aquagrid-platform.jar app.jar
RUN java -Djarmode=layertools -jar app.jar extract

# ---- Stage 4: runtime ----------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine

RUN addgroup -S aquagrid && adduser -S -G aquagrid -h /app aquagrid \
    && apk add --no-cache curl tzdata \
    && rm -rf /var/cache/apk/*

WORKDIR /app
COPY --from=extract --chown=aquagrid:aquagrid /extract/dependencies/ ./
COPY --from=extract --chown=aquagrid:aquagrid /extract/spring-boot-loader/ ./
COPY --from=extract --chown=aquagrid:aquagrid /extract/snapshot-dependencies/ ./
COPY --from=extract --chown=aquagrid:aquagrid /extract/application/ ./

USER aquagrid

EXPOSE 8080

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseZGC -XX:+ExitOnOutOfMemoryError \
-Djava.security.egd=file:/dev/urandom -Duser.timezone=UTC"

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl -fsS http://localhost:8080/actuator/health/readiness || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
