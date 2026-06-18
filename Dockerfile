FROM docker.io/library/eclipse-temurin:26-jdk AS builder
COPY . .
RUN ./gradlew hermes:bootJar

FROM docker.io/library/eclipse-temurin:26-jdk
WORKDIR /.
COPY --from=builder /hermes/build/libs/hermes-1.0.0.jar app.jar

ENTRYPOINT ["java", "-jar", "./app.jar"]
