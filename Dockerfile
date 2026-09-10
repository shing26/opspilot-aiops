# 多阶段：maven builder（依赖层缓存）→ 纯 JRE 运行时（DoD 无重量级依赖，运行时 <200MB）
# 构建上下文见 .dockerignore（.env/target/data/logs/backup 均不入镜像）
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
# 国内网络加速：aliyun 中央仓镜像
RUN mkdir -p /root/.m2 && printf '<settings><mirrors><mirror><id>aliyun</id><mirrorOf>central</mirrorOf><url>https://maven.aliyun.com/repository/public</url></mirror></mirrors></settings>' > /root/.m2/settings.xml
COPY pom.xml .
# BuildKit 缓存挂载：反复重建镜像时 maven 仓库不重复下载
RUN --mount=type=cache,target=/root/.m2 mvn -q -B dependency:go-offline
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -q -B package -DskipTests

FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app
RUN mkdir -p data logs offline/corpus
COPY --from=build /build/target/opspilot-gateway-1.0.0.jar app.jar
# 版本化语料随镜像走（ADR-0001 契约）：首启自愈逻辑据此灌库
COPY offline/corpus/chunks.jsonl offline/corpus/chunks.jsonl
EXPOSE 8081
HEALTHCHECK --interval=15s --timeout=5s --start-period=40s \
  CMD wget -qO- http://localhost:8081/actuator/health | grep -q '"status":"UP"' || exit 1
# 宿主机的中间件 host 由 compose environment 注入（REDIS_HOST/QDRANT_HOST/ES_HOST）；
# 首启若无读别名会自动全量入库（IngestionRunner.firstBootMissingAlias）
ENTRYPOINT ["java","-jar","/app/app.jar"]
