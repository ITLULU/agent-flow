# syntax=docker/dockerfile:1
# 多阶段构建：构建期要 Maven+JDK21，运行期只要 JRE21（镜像差 ~500MB，攻击面也小一截）。
# 构建上下文 = server/agent-flow-server（含 4 个模块与父 pom）。

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY . .
# BuildKit 缓存挂载：反复构建不再重下依赖；测试在 CI 跑，镜像构建只跳测试不跳编译。
# -s 指到 deploy/maven-settings.xml（bind 挂载，不进镜像层）：容器里没有宿主 ~/.m2，
# 不挂阿里云镜像时单次构建会拖到十分钟级。
RUN --mount=type=bind,source=deploy/maven-settings.xml,target=/root/settings.xml \
    --mount=type=cache,target=/root/.m2 \
    mvn -B -s /root/settings.xml -DskipTests clean package

FROM eclipse-temurin:21-jre AS runtime
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && useradd --system --uid 10001 --user-group --shell /usr/sbin/nologin --home-dir /home/agentflow agentflow \
 && mkdir -p /app/data/library /home/agentflow \
 && chown -R agentflow:agentflow /app /home/agentflow
WORKDIR /app
COPY --from=build --chown=agentflow:agentflow /src/server-application/target/server-application-0.1.0-SNAPSHOT.jar app.jar
COPY --chown=agentflow:agentflow deploy/docker-entrypoint.sh /app/docker-entrypoint.sh
RUN chmod +x /app/docker-entrypoint.sh

USER agentflow
ENV AGENTFLOW_LIBRARY_DIR=/app/data/library \
    JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -Dfile.encoding=UTF-8"
EXPOSE 8080
ENTRYPOINT ["/app/docker-entrypoint.sh"]
CMD ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
