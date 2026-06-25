# Stage 1: Build the application
FROM maven:3.9.6-eclipse-temurin-17 AS build
WORKDIR /app

# Copy pom.xml and download dependencies to leverage Docker cache
COPY pom.xml .
RUN maven_opts="-XX:+UseG1GC" mvn dependency:go-offline -B

# Copy src and build the packaged jar
COPY src ./src
RUN mvn clean package -DskipTests

# Stage 2: Create a secure, lightweight runtime container
FROM eclipse-temurin:17-jre
WORKDIR /app

# Create a non-privileged system user and group for runtime execution
RUN groupadd -r appgroup && useradd -r -g appgroup appuser

# Copy the built jar from the build stage
COPY --from=build /app/target/*.jar app.jar

# Set directory ownership to the non-root user
RUN chown -R appuser:appgroup /app

# Run as non-root user
USER appuser

# Expose Spring Boot port
EXPOSE 8080

# Environment variables for GC tuning and container resource awareness
ENV JAVA_OPTS="-XX:+UseG1GC -XX:+ExitOnOutOfMemoryError -XX:MaxRAMPercentage=75.0"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
