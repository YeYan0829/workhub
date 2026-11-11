# syntax=docker/dockerfile:1@sha256:4edf897a3ffa55b89f906fc8cc78afdb3f1834cc9c7083565e611a8a7d5fe99e
FROM maven:3.9.11-eclipse-temurin-17@sha256:e4a7ace3dc0d645ed97f8d9ad0b0d3f0b14fa8d150138f27f116d7105a639b82 AS build
WORKDIR /workspace
COPY pom.xml ./
COPY .mvn ./.mvn
COPY src/main ./src/main
RUN --mount=type=cache,target=/root/.m2/repository \
    mvn -B -ntp -Dmaven.repo.local=/root/.m2/repository -DskipTests package
COPY docker/Healthcheck.java /tmp/Healthcheck.java
RUN javac --release 17 -d /tmp/healthcheck /tmp/Healthcheck.java

FROM eclipse-temurin:17-jre-jammy@sha256:2a9eb226abb654c9d1f91b53cf638d20cb3abb726d1b18a67909c29515545469 AS runtime
RUN groupadd --gid 10001 workhub \
    && useradd --uid 10001 --gid workhub --no-create-home --home-dir /opt/workhub workhub \
    && mkdir -p /opt/workhub /var/lib/workhub \
    && chown workhub:workhub /var/lib/workhub
WORKDIR /opt/workhub
COPY --from=build /workspace/target/workhub-0.0.1.jar ./workhub.jar
COPY --from=build /tmp/healthcheck ./healthcheck
USER 10001:10001
ENV SERVER_ADDRESS=0.0.0.0 APP_PORT=8080 WORKHUB_MAIL_KEY_FILE=/var/lib/workhub/mail.key
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=10s --start-period=90s --retries=20 \
    CMD ["java", "-Xmx24m", "-XX:ActiveProcessorCount=1", "-cp", "/opt/workhub/healthcheck", "Healthcheck"]
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=60.0", "-XX:ActiveProcessorCount=2", "-jar", "/opt/workhub/workhub.jar"]
