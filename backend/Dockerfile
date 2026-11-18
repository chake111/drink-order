# 多阶段构建：构建阶段带 Maven + JDK，运行阶段只留 JRE，镜像更小且不带构建工具
# 注意：已验证（2026-11-14，Docker 29.9.3）：构建成功、镜像内以 uid=999(app) 运行、mvn 未泄漏到运行镜像

# ---------- 构建阶段 ----------
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build

# 先只拷 pom，依赖层可被缓存：源码改动不会让依赖重新下载
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q -DskipTests package

# ---------- 运行阶段 ----------
FROM eclipse-temurin:17-jre-jammy

# 非 root 运行：容器被拿下时不至于直接拿到 root
RUN groupadd --system app && useradd --system --gid app --home-dir /app --shell /usr/sbin/nologin app

WORKDIR /app
COPY --from=build /build/target/drink-server-*.jar /app/app.jar

# 上传目录（application.yml 里是 ./upload/，容器内用 DRINK_UPLOAD_PATH 覆盖到 /app/upload/）
RUN mkdir -p /app/upload && chown -R app:app /app

USER app
EXPOSE 8080

ENV TZ=Asia/Shanghai \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -Dfile.encoding=UTF-8"

# exec 保证 java 是 1 号进程，能收到 docker stop 的 SIGTERM 并优雅停机
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
