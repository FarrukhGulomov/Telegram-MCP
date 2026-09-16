# ---- build ---------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Dependencies first, so a source-only change does not re-download the world.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
# Security-audit finding SEC-18: tests are skipped here on purpose, not as an oversight -- the
# suite requires a live PostgreSQL instance the build stage has no network path to, since a
# plain `docker build` has no way to run a service container alongside it. Verification happens
# in .github/workflows/ci.yml instead, which runs the full suite against real PostgreSQL and
# gates the (separate) release-image build job on it passing; this Dockerfile is never invoked
# for a release outside that pipeline.
RUN mvn -B -q -DskipTests package

# ---- run -----------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-noble
WORKDIR /app

# The MOCK adapter needs no native library. To run the TDLIB adapter, mount or install
# libtdjson.so and put its directory on the loader path — see docs/tdlib-setup.md.
RUN useradd --system --uid 10001 --create-home tgmcp
COPY --from=build /build/target/telegram-consent-mcp-*.jar /app/app.jar
RUN chown -R tgmcp:tgmcp /app
USER tgmcp

EXPOSE 8080
ENV JAVA_OPTS=""
HEALTHCHECK --interval=15s --timeout=3s --start-period=40s --retries=5 \
  CMD ["sh", "-c", "wget -qO- http://127.0.0.1:8080/actuator/health | grep -q UP"]
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
