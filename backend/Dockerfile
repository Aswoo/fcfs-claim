# Stage 1: 빌드
FROM eclipse-temurin:17-jdk-alpine AS build
WORKDIR /app

# 의존성 캐시 레이어 (gradle 파일 먼저 복사)
COPY gradlew .
COPY gradle gradle
COPY build.gradle .
COPY settings.gradle .
RUN ./gradlew dependencies --no-daemon

# 소스 빌드
COPY src src
RUN ./gradlew bootJar --no-daemon

# Stage 2: 실행
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
