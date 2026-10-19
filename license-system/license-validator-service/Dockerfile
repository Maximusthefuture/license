# Build first: mvn -pl license-validator-service -am package
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY target/license-validator.jar /app/app.jar
USER 10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
