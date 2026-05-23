# syntax=docker/dockerfile:1.7

# --- Stage 1: build the fat distribution with Gradle ---
FROM eclipse-temurin:25-jdk-noble AS build
WORKDIR /workspace

COPY gradlew gradlew.bat settings.gradle gradle.properties ./
COPY gradle ./gradle
COPY app/build.gradle ./app/
COPY youtube-source/build.gradle ./youtube-source/

RUN chmod +x gradlew && ./gradlew --no-daemon --no-configuration-cache help -q || true

COPY app/src ./app/src
COPY youtube-source/src ./youtube-source/src

RUN ./gradlew --no-daemon --no-configuration-cache :app:installDist -x test


# --- Stage 2: minimal runtime ---
FROM eclipse-temurin:25-jre-noble

# yt-dlp is a Python script (#!/usr/bin/env python3) — python3 is required at runtime
# or every invocation dies with "/usr/bin/env: 'python3': No such file or directory".
RUN apt-get update \
 && apt-get install -y --no-install-recommends ffmpeg python3 curl ca-certificates \
 && curl -fsSL https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp \
        -o /usr/local/bin/yt-dlp \
 && chmod +x /usr/local/bin/yt-dlp \
 && /usr/local/bin/yt-dlp --version \
 && apt-get purge -y --auto-remove curl \
 && rm -rf /var/lib/apt/lists/*

RUN useradd --create-home --shell /usr/sbin/nologin --uid 10001 renzuy
USER renzuy
WORKDIR /home/renzuy/app

COPY --from=build --chown=renzuy:renzuy /workspace/app/build/install/app/ ./

# A writable home for prefix config (mounted via ECS volumes if persistence is wanted).
ENV APP_ENV=production \
    JAVA_TOOL_OPTIONS="--enable-native-access=ALL-UNNAMED" \
    RENZUY_CONFIG_DIR=/home/renzuy/app/config

RUN mkdir -p /home/renzuy/app/config

ENTRYPOINT ["./bin/app"]
