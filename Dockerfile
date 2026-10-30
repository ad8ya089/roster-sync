FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml ./
RUN mvn -B -q dependency:go-offline
COPY src ./src
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
