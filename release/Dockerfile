# syntax=docker/dockerfile:1.7
#
# Multi-target Dockerfile for the four Java services:
#   - collector            (Netty HTTP → Kafka)
#   - processor            (Kafka → render+hash → ClickHouse)
#   - query                (HTTP API in front of ClickHouse, consumed by UI)
#   - demo-spring-boot     (Spring Boot demo with the agent baked in)
#
# Build context: repo root.
# Builder stage compiles the agent reactor first (publishes shared libs
# to the in-image m2 cache), then infra and demos on top.

# -------------------------------------------------------------------
# Builder
# -------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-17@sha256:036d1a6f2965e4368157bb87f02cd31652a96918a26f7eb5ae45a0aa33f2cb8e AS builder
WORKDIR /src

COPY arachna-trace-shared/         ./arachna-trace-shared/
COPY arachna-trace-agents/         ./arachna-trace-agents/
COPY arachna-trace-jvm-extensions/ ./arachna-trace-jvm-extensions/
COPY arachna-trace-infra/          ./arachna-trace-infra/
COPY arachna-trace-demos/          ./arachna-trace-demos/

# Build the language-neutral umbrella first (codec, renderer, SPI APIs) →
# then the JVM agent (serializer + agent jar) → then the SPI reference
# impls → then infra and demos. Each step publishes its artifacts into
# the in-image m2 cache for downstream Maven resolution.
RUN --mount=type=cache,target=/root/.m2/repository \
    cd /src/arachna-trace-shared && \
    mvn -B -DskipTests install && \
    cd /src/arachna-trace-agents/jvm && \
    mvn -B -DskipTests install && \
    cd /src/arachna-trace-jvm-extensions && \
    mvn -B -DskipTests install && \
    cd /src/arachna-trace-infra && \
    mvn -B -DskipTests package && \
    cd /src/arachna-trace-demos/jvm && \
    mvn -B -DskipTests package

# -------------------------------------------------------------------
# Common JRE base for runtime stages
# -------------------------------------------------------------------
FROM bellsoft/liberica-runtime-container:jre-17-musl@sha256:dc909359d7077eb7f98c6f0bdcd020399641a159db5dbf22de9735955efb3388 AS jre-base
RUN addgroup -S arachna-trace && adduser -S -G arachna-trace arachna-trace
WORKDIR /app
USER arachna-trace

# -------------------------------------------------------------------
# Collector
# -------------------------------------------------------------------
FROM jre-base AS collector
COPY --from=builder /src/arachna-trace-infra/record-collector-server/target/record-collector-server.jar /app/app.jar
COPY release/configs/collector.cfg /app/config.cfg
EXPOSE 8099
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
CMD ["config=/app/config.cfg"]

# -------------------------------------------------------------------
# Processor
# -------------------------------------------------------------------
FROM jre-base AS processor
COPY --from=builder /src/arachna-trace-infra/record-processor-server/target/record-processor-server.jar /app/app.jar
COPY release/configs/processor.cfg /app/config.cfg
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
CMD ["config=/app/config.cfg"]

# -------------------------------------------------------------------
# Query (HTTP API for UI)
# -------------------------------------------------------------------
FROM jre-base AS query
COPY --from=builder /src/arachna-trace-infra/record-query-server/target/record-query-server.jar /app/app.jar
COPY release/configs/query.cfg /app/config.cfg
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
CMD ["config=/app/config.cfg"]

# -------------------------------------------------------------------
# Demo Spring Boot (agent baked in)
# -------------------------------------------------------------------
FROM jre-base AS demo-spring-boot
COPY --from=builder /src/arachna-trace-agents/jvm/core/agent/target/arachna-trace-agent.jar /app/agent.jar
COPY --from=builder /src/arachna-trace-demos/jvm/demo-spring-boot/target/ArachnaTraceDemoSpringBoot-0.0.1-SNAPSHOT.jar /app/app.jar
COPY release/configs/demo-agent.cfg /app/agent.cfg
EXPOSE 8080
ENTRYPOINT ["java", "-javaagent:/app/agent.jar=config=/app/agent.cfg", "-jar", "/app/app.jar"]
