# ============================================
# Agent-QR 后端 Dockerfile (Spring Boot)
# 多阶段构建
# ============================================

# ---- 阶段1: Maven 构建 ----
FROM maven:3.9-eclipse-temurin-21 AS builder

WORKDIR /build

# 一次性复制整个构建上下文。
# 这里刻意使用 COPY . . 而不是逐模块 COPY：根 pom.xml 的 <modules> 每新增一个模块，
# 逐模块写法都必须同步改 Dockerfile，否则 Maven 会在反应堆初始化阶段报
# "Child module ... does not exist" 导致镜像构建失败（这是"每次加模块都会忘记改"的结构性诱因）。
# 上下文裁剪由 .dockerignore 负责（target/、node_modules/、uploads/、log/、.git/、doc/ 等）。
COPY . .

# 一步构建：package 会自动下载依赖
# -pl agent-qr-web -am: 只构建 web 模块及其依赖模块，但 Maven 仍会解析根 pom 的全部 <modules>，
#                       因此 build 上下文中必须存在全部模块目录（由 COPY . . 保证）。
# -Dmaven.test.skip=true: 跳过测试
RUN mvn clean package -pl agent-qr-web -am -Dmaven.test.skip=true

# ---- 阶段2: 运行时镜像 ----
FROM eclipse-temurin:21-jre-alpine

# 安装 curl 用于健康检查
RUN apk add --no-cache curl

# 创建非 root 用户
RUN addgroup -S appgroup && adduser -S appuser -G appgroup

WORKDIR /app

# 复制构建产物
COPY --from=builder /build/agent-qr-web/target/*.jar app.jar

# 创建上传目录
RUN mkdir -p /app/uploads && chown -R appuser:appgroup /app

USER appuser

EXPOSE 9090

# 健康检查
HEALTHCHECK --interval=30s --timeout=10s --start-period=90s --retries=5 \
  CMD curl -f -s -o /dev/null http://localhost:9090/api/auth/login || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
