# syntax=docker/dockerfile:1.7
#
# Multi-target Dockerfile for the four Java services:
#   - collector            (Netty HTTP → Kafka)
#   - processor            (Kafka → render+hash → ClickHouse)
#   - query                (HTTP API in front of ClickHouse, consumed by UI)
#   - demo-spring-boot     (Spring Boot demo with the agent baked in)
#
# Build context: repo root.
# Builder stage compiles the entire reactor once; each runtime stage
# COPYs only its own jar(s) so layers stay small.

# -------------------------------------------------------------------
# Builder
# -------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /src

# Copy the whole agent reactor and build everything in one pass.
# The reactor is cohesive — building only one module forces -am to
# compile most of the rest anyway, so the simpler approach is best.
COPY deepflow-agent/ ./deepflow-agent/

WORKDIR /src/deepflow-agent
RUN --mount=type=cache,target=/root/.m2/repository \
    mvn -B -DskipTests package

# -------------------------------------------------------------------
# Common JRE base for runtime stages
# -------------------------------------------------------------------
FROM eclipse-temurin:17-jre-alpine AS jre-base
RUN addgroup -S deepflow && adduser -S -G deepflow deepflow
WORKDIR /app
USER deepflow

# -------------------------------------------------------------------
# Collector
# -------------------------------------------------------------------
FROM jre-base AS collector
COPY --from=builder /src/deepflow-agent/server/record-collector-server/target/record-collector-server.jar /app/app.jar
COPY release/configs/collector.cfg /app/config.cfg
EXPOSE 8099
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
CMD ["config=/app/config.cfg"]

# -------------------------------------------------------------------
# Processor
# -------------------------------------------------------------------
FROM jre-base AS processor
COPY --from=builder /src/deepflow-agent/server/record-processor-server/target/record-processor-server.jar /app/app.jar
COPY release/configs/processor.cfg /app/config.cfg
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
CMD ["config=/app/config.cfg"]

# -------------------------------------------------------------------
# Query (HTTP API for UI)
# -------------------------------------------------------------------
FROM jre-base AS query
COPY --from=builder /src/deepflow-agent/server/record-query-server/target/record-query-server.jar /app/app.jar
COPY release/configs/query.cfg /app/config.cfg
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
CMD ["config=/app/config.cfg"]

# -------------------------------------------------------------------
# Demo Spring Boot (agent baked in)
# -------------------------------------------------------------------
FROM jre-base AS demo-spring-boot
COPY --from=builder /src/deepflow-agent/core/agent/target/deepflow-agent.jar /app/agent.jar
COPY --from=builder /src/deepflow-agent/demos/demo-spring-boot/target/DeepFlowDemoSpringBoot-0.0.1-SNAPSHOT.jar /app/app.jar
COPY release/configs/demo-agent.cfg /app/agent.cfg
EXPOSE 8080
ENTRYPOINT ["java", "-javaagent:/app/agent.jar=config=/app/agent.cfg", "-jar", "/app/app.jar"]
