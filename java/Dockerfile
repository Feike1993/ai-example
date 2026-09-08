FROM eclipse-temurin:25-jdk-jammy AS builder

WORKDIR /workspace/java
COPY java/gradlew ./gradlew
COPY java/gradle ./gradle
COPY java/settings.gradle java/build.gradle java/gradle.properties ./
RUN chmod +x ./gradlew

# BuildKit cache：跨构建复用 ~/.gradle（发行包 + 依赖），避免每次重下
RUN --mount=type=cache,target=/root/.gradle \
    set -eu; \
    for attempt in 1 2 3; do \
        ./gradlew --no-daemon dependencies && exit 0; \
        test "$attempt" -eq 3 && exit 1; \
        sleep 5; \
    done

COPY java/src ./src
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon bootJar

FROM eclipse-temurin:25-jdk-jammy

WORKDIR /app
COPY --from=builder --chown=10001:10001 /workspace/java/build/libs/ai-example.jar app.jar

USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-Dfile.encoding=UTF-8", "-Dconsole.encoding=UTF-8", "--enable-native-access=ALL-UNNAMED", "-jar", "/app/app.jar"]
