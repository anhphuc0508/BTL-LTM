FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml ./
COPY shared/ shared/
COPY server/ server/
COPY client/pom.xml client/pom.xml
RUN mvn -B -pl server -am package -DskipTests

FROM eclipse-temurin:17-jre
WORKDIR /app
RUN mkdir -p logs && chown -R 10001:10001 /app
COPY --from=build /build/server/target/server-1.0-SNAPSHOT.jar server.jar
USER 10001:10001
EXPOSE 5000
ENTRYPOINT ["java", "-jar", "server.jar"]
