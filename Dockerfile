# syntax=docker/dockerfile:1

FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
# Primero solo lo que define las dependencias, para que Docker las cachee mientras no cambie el build.
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
RUN ./gradlew --no-daemon dependencies > /dev/null
COPY src src
COPY newrelic newrelic
RUN ./gradlew --no-daemon bootJar copyNewRelic

FROM eclipse-temurin:21-jre
RUN groupadd --system spring && useradd --system --gid spring spring
WORKDIR /app
COPY --from=build /app/build/libs/app.jar app.jar
# El agente de New Relic con su newrelic.yml. No hace nada salvo que NEW_RELIC_AGENT_ENABLED=true (en las VMs).
COPY --from=build /app/build/newrelic newrelic
USER spring
EXPOSE 8080
ENTRYPOINT ["java", "-javaagent:/app/newrelic/newrelic.jar", "-jar", "app.jar"]
