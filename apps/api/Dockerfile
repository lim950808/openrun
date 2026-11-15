# syntax=docker/dockerfile:1
# openrun API (ADR-0008 §4). Build context: repository root.
#   docker build -f apps/api/Dockerfile -t openrun-api .
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle gradle
RUN ./gradlew --version --no-daemon
COPY apps/api apps/api
COPY modules modules
RUN ./gradlew :apps:api:bootJar --no-daemon \
 && cp "$(ls apps/api/build/libs/*.jar | grep -v -- '-plain.jar' | head -n 1)" /workspace/app.jar

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 openrun
WORKDIR /app
COPY --from=build /workspace/app.jar app.jar
USER openrun
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
