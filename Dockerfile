FROM maven:3.9.9-eclipse-temurin-21 AS build

WORKDIR /workspace

COPY pom.xml ./
COPY src ./src

RUN mvn -q -DskipTests package

FROM eclipse-temurin:21-jre

RUN groupadd --gid 10001 --system sessionagent \
    && useradd --uid 10001 --system --gid sessionagent --home-dir /nonexistent --shell /usr/sbin/nologin sessionagent

WORKDIR /app

COPY --from=build /workspace/target/session-agent-runtime-0.0.1-SNAPSHOT.jar app.jar

RUN mkdir /app/logs \
    && chown sessionagent:sessionagent /app/logs

USER sessionagent:sessionagent

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
