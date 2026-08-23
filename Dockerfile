  # Build stage
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
COPY nzb-streamer-lib/pom.xml nzb-streamer-lib/pom.xml
COPY nzb-streamer-app/pom.xml nzb-streamer-app/pom.xml
RUN mvn dependency:go-offline -q
COPY nzb-streamer-lib/src nzb-streamer-lib/src
COPY nzb-streamer-app/src nzb-streamer-app/src
RUN mvn -pl nzb-streamer-app -am package -DskipTests -q

  # Run stage
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/nzb-streamer-app/target/nzb-streamer-app-1.0-SNAPSHOT.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
