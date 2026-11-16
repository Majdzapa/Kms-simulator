# Stage 1: Build the Spring Boot JAR inside Docker (no host Maven/JDK 25 required)
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /build
COPY pom.xml ./pom.xml
COPY src ./src
RUN mvn package -DskipTests --batch-mode

# Stage 2: Runtime image
FROM eclipse-temurin:25-jre-jammy

RUN apt-get update && apt-get install -y --no-install-recommends \
    curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

RUN mkdir -p /data/pki

COPY --from=build /build/target/kms-simulator-*.jar app.jar

EXPOSE 8090

ENTRYPOINT ["java", "-jar", "app.jar"]
