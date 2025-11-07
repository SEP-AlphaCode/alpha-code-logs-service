# Stage 1: Build với Maven
FROM maven:3.9-eclipse-temurin-24-alpine AS build

WORKDIR /app

COPY pom.xml .

# Copy source code
COPY src ./src
COPY src/main/proto ./src/main/proto

RUN mvn clean package -DskipTests

# Stage 2: Run với Temurin JDK
FROM eclipse-temurin:24-jdk-alpine

WORKDIR /app

COPY --from=build /app/target/alpha-code-logs-service-0.0.1-SNAPSHOT.jar ./logs.jar

EXPOSE 8083

ENTRYPOINT ["java", "-jar", "logs.jar"]
