# --- Build stage ----------------------------------------------------------
# Cache Maven deps in their own layer so source-only edits don't refetch them.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace

COPY pom.xml ./
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q dependency:go-offline

COPY src ./src
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q -DskipTests package && \
    cp target/faceless-chronicle-ai-*.jar /workspace/app.jar

# --- Runtime stage --------------------------------------------------------
# JRE-only image keeps the runtime small. ffmpeg is bundled because
# VideoPipelineService shells out to it for audio probing and concat.
FROM eclipse-temurin:21-jre AS runtime
RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
RUN useradd --system --uid 1001 chronicle && chown chronicle /app
USER chronicle

COPY --from=build --chown=chronicle:chronicle /workspace/app.jar /app/app.jar

ENV JAVA_OPTS="-XX:+UseG1GC -XX:MaxRAMPercentage=40.0"
EXPOSE 8090

# Render (and most managed PaaS) inject $PORT for the app to bind to.
# Falls back to 8090 so ECS / docker-compose keep working unchanged.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar --server.port=${PORT:-8090}"]