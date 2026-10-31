# Docker's predefined proxy build args reach RUN steps in every stage.
# Compose defaults them to empty. Keep network-specific values outside this file.
FROM node:24.22.2-alpine AS frontend
WORKDIR /frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
ARG VITE_STYLEGUIDE=false
ARG VITE_DEMO_HINTS=false
ENV VITE_STYLEGUIDE=$VITE_STYLEGUIDE VITE_DEMO_HINTS=$VITE_DEMO_HINTS
RUN npm run build

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml ./
RUN mvn -B -q dependency:go-offline
COPY src ./src
COPY --from=frontend /frontend/dist ./src/main/resources/static
RUN mvn -B -DskipTests package

FROM eclipse-temurin:21-jre
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 roster \
    && useradd --uid 10001 --gid roster --no-create-home roster \
    && mkdir -p /app /data && chown roster:roster /app /data
WORKDIR /app
COPY --from=build --chown=roster:roster /workspace/target/roster-sync-0.0.1-SNAPSHOT.jar /app/roster-sync.jar
USER roster:roster
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080 8081
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/roster-sync.jar"]
