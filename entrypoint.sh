#!/bin/sh
# Container entrypoint.
#
# If YT_DLP_COOKIES_DATA is injected (from Secrets Manager via the ECS task
# definition), materialize it to the path YT_DLP_COOKIES — that's what yt-dlp
# reads via --cookies. Without this, the Fargate egress IP hits YouTube's
# bot-detection wall on roughly every other video.
#
# Both vars must be set for materialization to happen. Either being unset is
# fine for local dev (skip silently, the Java layer just omits --cookies).
set -eu

if [ -n "${YT_DLP_COOKIES_DATA:-}" ] && [ -n "${YT_DLP_COOKIES:-}" ]; then
    mkdir -p "$(dirname "$YT_DLP_COOKIES")"
    # printf, not echo: echo -n is non-portable, and we must not append a
    # trailing newline that would corrupt the Netscape cookies file header.
    printf '%s' "$YT_DLP_COOKIES_DATA" > "$YT_DLP_COOKIES"
    chmod 600 "$YT_DLP_COOKIES" 2>/dev/null || true
    # Scrub the env so the cookie contents don't leak into child processes
    # (yt-dlp, ffmpeg) or any future logging that dumps env.
    unset YT_DLP_COOKIES_DATA
fi

exec ./bin/app "$@"
