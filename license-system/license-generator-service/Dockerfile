# Build first: mvn -pl license-generator-service -am package
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY target/license-generator.jar /app/app.jar
# Non-root; the pod's fsGroup lets it read the key Secret (/keys) and write the audit volume (/data/audit).
USER 10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
