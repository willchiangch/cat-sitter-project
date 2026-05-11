# Stage 1: Build stage
FROM maven:3.9.6-eclipse-temurin-21-jammy AS build
WORKDIR /app
COPY pom.xml .
# 預先下載依賴，利用 Docker Layer Cache
RUN mvn dependency:go-offline -B
COPY src ./src
RUN mvn package -DskipTests

# Stage 2: Runtime stage
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

# 設定全域時區為 UTC (符合 SD-GLOBAL-SPEC 1.2)
ENV TZ=UTC
RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

# 從構建階段拷貝 JAR 檔
COPY --from=build /app/target/*.jar app.jar

# 暴露 Spring Boot 預設埠號
EXPOSE 8080

# 啟動參數：優化記憶體配置並設定無頭模式
ENTRYPOINT ["java", "-Djava.security.egd=file:/dev/./urandom", "-Duser.timezone=UTC", "-jar", "app.jar"]
