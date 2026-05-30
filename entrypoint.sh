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

# Automatically resolve the delegated IPv6 prefix from the ECS task's ENI if available
if [ -n "${ECS_CONTAINER_METADATA_URI_V4:-}" ]; then
    ENI_ID=$(curl -s "$ECS_CONTAINER_METADATA_URI_V4/task" | python3 -c '
import sys, json
try:
    for a in json.load(sys.stdin).get("Attachments", []):
        if a.get("Type") == "ElasticNetworkInterface":
            for d in a.get("Details", []):
                if d.get("Name") == "networkInterfaceId":
                    print(d.get("Value"))
except Exception:
    pass
')
    if [ -n "$ENI_ID" ]; then
        IPV6_PREFIX=$(aws ec2 describe-network-interfaces --network-interface-ids "$ENI_ID" --query 'NetworkInterfaces[0].Ipv6Prefixes[0].Ipv6Prefix' --output text 2>/dev/null || true)
        if [ -n "$IPV6_PREFIX" ] && [ "$IPV6_PREFIX" != "None" ]; then
            export IPV6_BLOCK="$IPV6_PREFIX"
            echo "[entrypoint] Discovered IPv6 prefix for rotation: $IPV6_BLOCK"
            # Route the entire block to the loopback interface so the container can bind to any address inside it natively.
            ip -6 route add local "$IPV6_BLOCK" dev lo 2>/dev/null || true
        fi
    fi
fi

exec ./bin/app "$@"
