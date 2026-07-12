FROM maven:3.9.9-eclipse-temurin-17 AS build

WORKDIR /workspace
COPY pom.xml mvnw ./
COPY .mvn ./.mvn
RUN chmod +x mvnw
COPY src ./src
RUN ./mvnw -DskipTests package

FROM eclipse-temurin:17-jre

WORKDIR /app
RUN useradd --system --create-home --uid 10001 research
COPY --from=build /workspace/target/*.jar /app/research-assistant.jar
RUN mkdir -p /data/papers && chown -R research:research /app /data
USER research

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/research-assistant.jar"]
