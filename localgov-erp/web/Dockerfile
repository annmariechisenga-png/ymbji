# syntax=docker/dockerfile:1.7

FROM maven:3.9.8-eclipse-temurin-25 AS build
WORKDIR /workspace
COPY . /workspace
RUN mvn -q -f /workspace/pom.xml -pl web -am package -DskipTests

FROM eclipse-temurin:25-jre
WORKDIR /app
RUN addgroup --system appgroup && adduser --system appuser --ingroup appgroup
COPY --from=build /workspace/web/target/web-1.0.0-SNAPSHOT.jar /app/app.jar
RUN chown appuser:appgroup /app/app.jar
USER appuser
EXPOSE 8080
ENV JAVA_OPTS=""
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
