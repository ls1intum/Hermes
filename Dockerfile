FROM openjdk:17
WORKDIR /.
COPY hermes/build/libs/hermes-0.0.1-SNAPSHOT.jar ./app.jar

ENTRYPOINT ["java", "-jar", "./app.jar"]
