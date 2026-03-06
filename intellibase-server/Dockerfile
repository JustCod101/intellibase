# ==================== Stage 1: Build ====================
FROM maven:3.9-eclipse-temurin-17 AS builder

WORKDIR /build

# 先复制 POM 文件，利用 Docker 层缓存加速依赖下载
COPY pom.xml .
RUN mvn dependency:go-offline -B

# 复制源码并打包（跳过测试，加速构建）
COPY src ./src
RUN mvn package -DskipTests -B && \
    mv target/*.jar app.jar

# ==================== Stage 2: Runtime ====================
FROM eclipse-temurin:17-jre-jammy

LABEL maintainer="IntelliBase Team"

WORKDIR /app

# 创建非 root 用户
RUN groupadd -r appuser && useradd -r -g appuser appuser

# 从构建阶段复制 JAR
COPY --from=builder /build/app.jar app.jar

# 使用非 root 用户运行
USER appuser

EXPOSE 8080

# JVM 调优参数：容器感知内存、G1GC、关闭偏向锁
ENTRYPOINT ["java", \
    "-XX:+UseContainerSupport", \
    "-XX:MaxRAMPercentage=75.0", \
    "-XX:+UseG1GC", \
    "-XX:-UseBiasedLocking", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "-jar", "app.jar"]
