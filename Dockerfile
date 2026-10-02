FROM maven:3.9.16-eclipse-temurin-17-noble AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:17-jre-noble
RUN useradd --system --uid 10001 --create-home app
WORKDIR /app
COPY --from=build /build/target/seat-reservation-at-scale-0.1.0-SNAPSHOT.jar app.jar
USER 10001
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70.0"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
