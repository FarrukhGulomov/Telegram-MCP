# ---- tdlib build ---------------------------------------------------------------------
# Builds libtdjson.so from a pinned upstream commit (TDLib does not tag releases regularly
# -- see docs/tdlib-setup.md -- so a commit SHA, not a floating branch, keeps this build
# reproducible). Only the `tdjson` CMake target is built, not the full TDLib CLI/tests, to
# keep this stage as fast as a from-source TDLib build can be.
#
# Built unconditionally, even for a deployment that only ever runs the MOCK adapter: the
# extra build time is the price of every deployment coming from one branch and one image,
# rather than maintaining a second Dockerfile that only diverges here.
FROM debian:bookworm-slim AS tdlib-build
RUN apt-get update && apt-get install -y --no-install-recommends \
      ca-certificates git make cmake g++ gperf zlib1g-dev libssl-dev \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /td
RUN git init -q . \
    && git remote add origin https://github.com/tdlib/td.git \
    && git fetch --depth 1 origin d1085f9cebc5a62379991ae1652673954f229c1f \
    && git checkout -q FETCH_HEAD
RUN mkdir build && cd build \
    && cmake -DCMAKE_BUILD_TYPE=Release .. \
    # -j2 rather than nproc: compiling the generated td_api sources is memory-hungry per
    # job, and a build-machine OOM kill is a worse failure mode than a slower build.
    && cmake --build . --target tdjson -j2

# ---- build (app) -----------------------------------------------------------------------
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
FROM eclipse-temurin:25-jre-noble
WORKDIR /app

RUN useradd --system --uid 10001 --create-home tgmcp
COPY --from=build /build/target/telegram-consent-mcp-*.jar /app/app.jar
COPY --from=tdlib-build /td/build/libtdjson.so* /usr/local/lib/
RUN ldconfig
ENV LD_LIBRARY_PATH=/usr/local/lib
RUN chown -R tgmcp:tgmcp /app

EXPOSE 8080
ENV JAVA_OPTS=""
HEALTHCHECK --interval=15s --timeout=3s --start-period=40s --retries=5 \
  CMD ["sh", "-c", "wget -qO- http://127.0.0.1:8080/actuator/health | grep -q UP"]
# Deliberately no `USER tgmcp` above: TGMCP_TDLIB_DIR is a runtime volume mount (Railway
# mounts it root-owned), which `chown` at build time cannot touch since it doesn't exist
# until the container starts. The container starts as root, fixes ownership of whatever is
# actually mounted there on every start (idempotent, and cheap even once it holds TDLib's
# database), then drops to the unprivileged `tgmcp` user for the JVM itself. Real-account
# testing on this branch caught this the first time the TDLIB adapter ever ran against a
# real mounted volume: setTdlibParameters failed with "Permission denied" creating
# TGMCP_TDLIB_DIR/files, since the app previously ran as `tgmcp` from container start with
# no chance to fix a root-owned mount it has no permission to chown itself.
ENTRYPOINT ["sh", "-c", "dir=\"${TGMCP_TDLIB_DIR:-/tmp/tdlib}\"; mkdir -p \"$dir\" && chown -R tgmcp:tgmcp \"$dir\" && exec su --preserve-environment -s /bin/sh tgmcp -c 'exec \"$JAVA_HOME/bin/java\" $JAVA_OPTS -jar /app/app.jar'"]
