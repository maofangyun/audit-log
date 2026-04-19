FROM maven:3.9.6-eclipse-temurin-17-alpine AS build
WORKDIR /workspace/app
COPY pom.xml .
COPY src src
RUN mvn clean package -DskipTests

FROM eclipse-temurin:17-jre-alpine
VOLUME /tmp
COPY --from=build /workspace/app/target/*SNAPSHOT.jar app.jar
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app.jar"]
