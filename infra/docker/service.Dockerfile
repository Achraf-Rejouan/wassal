# Multi-stage build for any Spring Boot service module.
# Base images are pinned by digest in CI (security T-09); tags are used locally for legibility.

FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /src
COPY gradle gradle
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY contracts contracts
ARG MODULE
COPY ${MODULE} ${MODULE}
RUN ./gradlew --no-daemon :${MODULE}:bootJar -x test

FROM eclipse-temurin:21-jre-alpine AS runtime
RUN addgroup -S app && adduser -S app -G app && apk add --no-cache wget
WORKDIR /app
ARG MODULE
COPY --from=build /src/${MODULE}/build/libs/*.jar app.jar
USER app
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
