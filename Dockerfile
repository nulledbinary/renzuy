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

# yt-dlp is a Python script; python3 is required at runtime. We install via pip
# (rather than the standalone curl-downloaded zipapp) specifically to pull
# curl_cffi alongside it. curl_cffi gives yt-dlp the --impersonate flag, which
# rewrites the TLS ClientHello and HTTP/2 frame sequence to match real Chrome.
# The default Python/urllib fingerprint is now an independent signal YouTube
# scores against on top of the source ASN, so from a Fargate egress IP the bare
# Python stack trips the bot-detection wall even when session cookies are valid.
# --break-system-packages is needed on Ubuntu 24.04 (Noble): PEP 668 marks the
# system Python externally-managed, but the container image *is* the environment
# so a system-wide install is the intended placement.
RUN set -eux \
 && apt-get update \
 && apt-get install -y --no-install-recommends ffmpeg python3 python3-pip ca-certificates curl unzip iproute2 nodejs \
 && python3 -m pip install --no-cache-dir --break-system-packages "yt-dlp[default,curl-cffi]" \
 && python3 -m pip install --no-cache-dir --break-system-packages bgutil-ytdlp-pot-provider \
 # The bgutil plugin forwards yt-dlp's --proxy to the PoT sidecar, which then
 # fetches its BotGuard challenge from google.com through that proxy. Bright
 # Data zones 403 google.com at the CONNECT stage (only youtube.com/googlevideo
 # are allowed), so a forwarded proxy means no token is ever minted and yt-dlp
 # stalls on the provider until the 25 s process timeout. Strip the forwarding:
 # the sidecar shares this task's network namespace and mints directly from the
 # task IP, which Google serves the challenge to. The grep makes an upstream
 # rewrite of this line a loud build failure instead of a silent regression.
 && PLUGIN_FILE=$(python3 -c "import importlib.util; print(importlib.util.find_spec('yt_dlp_plugins.extractor.getpot_bgutil_http').origin)") \
 && sed -i "s/'proxy': request\.request_proxy,/'proxy': None,/" "$PLUGIN_FILE" \
 && grep -q "'proxy': None," "$PLUGIN_FILE" \
 && curl -s "https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip" -o "awscliv2.zip" \
 && unzip -q awscliv2.zip \
 && ./aws/install \
 && rm -rf aws awscliv2.zip \
 && apt-get remove -y unzip \
 && apt-get autoremove -y \
 && yt-dlp --version \
 && yt-dlp --list-impersonate-targets | grep -qi chrome \
 && rm -rf /var/lib/apt/lists/* /root/.cache

# bgutil-ytdlp-pot-provider is the yt-dlp plugin that talks to a PoToken
# provider over HTTP and inserts the resulting GVS PoToken into the Innertube
# request. The provider itself runs as a sidecar container in the ECS task
# (brainicism/bgutil-ytdlp-pot-provider) — both containers share the task's
# network namespace under awsvpc, so the plugin reaches it at localhost:4416.
# YT_DLP_POT_PROVIDER_URL is read by the Java layer and propagated to yt-dlp.

RUN useradd --create-home --shell /usr/sbin/nologin --uid 10001 renzuy
USER renzuy
WORKDIR /home/renzuy/app

COPY --from=build --chown=renzuy:renzuy /workspace/app/build/install/app/ ./
COPY --chown=renzuy:renzuy entrypoint.sh /home/renzuy/app/entrypoint.sh

# A writable home for prefix config (mounted via ECS volumes if persistence is wanted).
# YT_DLP_COOKIES is the path the entrypoint writes the cookie file to when the
# YT_DLP_COOKIES_DATA secret is injected by ECS — yt-dlp then reads it via --cookies.
ENV APP_ENV=production \
    JAVA_TOOL_OPTIONS="--enable-native-access=ALL-UNNAMED" \
    RENZUY_CONFIG_DIR=/home/renzuy/app/config \
    YT_DLP_COOKIES=/home/renzuy/app/config/youtube-cookies.txt \
    YT_DLP_POT_PROVIDER_URL=http://localhost:4416

# Strip CR characters in case the source was uploaded from a Windows
# checkout — `exec ./entrypoint.sh` fails with "no such file or directory"
# on CRLF endings because the kernel looks up `/bin/sh\r` as the interpreter.
RUN mkdir -p /home/renzuy/app/config \
 && sed -i 's/\r$//' /home/renzuy/app/entrypoint.sh \
 && chmod +x /home/renzuy/app/entrypoint.sh

ENTRYPOINT ["./entrypoint.sh"]
