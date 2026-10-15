FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
COPY target/tf-chat.jar /app/app.jar
EXPOSE 8102
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
