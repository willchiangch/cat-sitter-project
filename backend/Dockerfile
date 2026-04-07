# Stage 1: Build JAR
FROM maven:3.9-eclipse-temurin-21-alpine AS build
WORKDIR /app

# Copy pom.xml for dependency caching
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Copy source and build
COPY src ./src
RUN mvn clean package -DskipTests -B

# Stage 2: Runtime
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Copy built JAR
COPY --from=build /app/target/*.jar app.jar

# Configuration
ENV PORT=8080
EXPOSE 8080

# 修正：使用 sh -c 以確保 ${PORT} 環境變數能被 Shell 正式解析並傳入 Java
ENTRYPOINT ["sh", "-c", "java -Djava.security.egd=file:/dev/./urandom -Dserver.port=${PORT} -jar app.jar"]
