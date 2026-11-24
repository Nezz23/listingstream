# Build stage: compile and package the Spring Boot jar (tests run in CI, not in the image build)
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package -DskipTests

# Run stage: JRE only, non-root user
FROM eclipse-temurin:21-jre
RUN useradd --system --uid 1001 stream
WORKDIR /app
COPY --from=build /src/target/rentguard-*.jar app.jar
USER stream
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
