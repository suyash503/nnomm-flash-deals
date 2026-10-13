# Build stage: compile and lay out the app with Gradle's installDist.
FROM eclipse-temurin:17-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies > /dev/null
COPY src src
RUN ./gradlew --no-daemon installDist -x test

# Run stage: just the JRE and the built app.
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /src/build/install/nnomm-flash-deals /app
RUN useradd --system app && chown -R app /app
USER app
EXPOSE 8080
ENTRYPOINT ["/app/bin/nnomm-flash-deals"]
