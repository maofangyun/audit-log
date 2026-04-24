FROM eclipse-temurin:17-jre-alpine
VOLUME /tmp
# 使用本地构建好的 JAR 文件，避免 Docker 内部下载 Maven 依赖失败
COPY target/*SNAPSHOT.jar app.jar
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app.jar"]
