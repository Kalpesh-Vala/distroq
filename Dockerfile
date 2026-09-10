# Two stages: the build tools never reach the runtime image, so a shell in a running container has
# no compiler, no Maven, no source and no build cache to work with.
#
# Image tags are pinned to a major line rather than `latest`. A deployment should go further and
# pin the digest - see OPERATIONS.md - which is the only form of pinning that is actually
# immutable; a tag can be moved.

FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /build

# dependencies first, in their own layer: source changes are frequent and the dependency set is
# not, so a code-only change reuses this layer instead of re-resolving the whole tree
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline

COPY src/ src/
# .git is copied last and is optional: git-commit-id-maven-plugin is configured with
# failOnNoGitDirectory=false, so a source tarball with no history still builds and simply has no
# git block in /actuator/info
COPY .git/ .git/
RUN ./mvnw -B -q clean package -DskipTests \
    && mv target/distroq-*.jar /build/distroq.jar


FROM eclipse-temurin:21-jre-alpine AS runtime

# a fixed uid/gid rather than a name: a volume mounted from the host is owned by a number, and a
# name that resolves differently in another image would silently produce a permission error
RUN addgroup -g 10001 -S distroq \
    && adduser -u 10001 -S -G distroq -h /app distroq

WORKDIR /app
COPY --from=build --chown=10001:10001 /build/distroq.jar /app/distroq.jar

# no secrets are baked in. Every credential arrives through the environment at run time, and
# .dockerignore keeps .env and any local override file out of the build context entirely.
ENV SPRING_PROFILES_ACTIVE=production \
    JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/urandom"

USER 10001:10001
EXPOSE 8080

# the container's own opinion of whether it is ready. An orchestrator should use the same endpoint;
# this exists so `docker compose` alone can tell a starting instance from a broken one.
HEALTHCHECK --interval=10s --timeout=3s --start-period=45s --retries=6 \
    CMD wget -qO- http://127.0.0.1:8080/actuator/health/readiness | grep -q '"status":"UP"' || exit 1

# exec form via sh -c so JAVA_OPTS is expanded, but the JVM still becomes PID 1 and therefore
# still receives SIGTERM directly - which is what starts the graceful shutdown sequence
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/distroq.jar"]
