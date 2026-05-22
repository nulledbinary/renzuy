# youtube-source

A standalone, low-latency YouTube audio resolver for the **renzuy** Discord bot.

It is a plain Java library — no JDA, no Spring, no dependency on the bot. Give it a
`/play` argument; it hands back a directly-playable stream reference. The bot's `app`
module feeds that URL to ffmpeg; nothing here knows about Discord.

## What it does

`YoutubeSource.resolve(query)` accepts a YouTube URL, a non-YouTube URL, or a
free-text search term, and returns an [`AudioReference`](src/main/java/renzuy/youtube/AudioReference.java)
— title, author, duration, and a CDN URL that ffmpeg can read immediately.

## Hybrid resolution — fastest path first

| Step | Path | Cost |
|---|---|---|
| 1 | **Cache** — repeat play of the same video | instant, no I/O |
| 2 | **Innertube** — in-process call to YouTube's internal API | one warm HTTP/2 request |
| 3 | **yt-dlp fallback** — only when Innertube can't, plus non-YouTube links | a subprocess |

The old resolver shelled out to `yt-dlp` for *every* play and blocked until the
process exited — Python cold-start alone was ~0.5–2 s. The Innertube fast path
removes the subprocess entirely: a warm cache-miss resolves in well under half a
second, and a cache hit is immediate.

### Latency techniques

- **Warm HTTP/2 connection** — one shared `HttpClient`; after the first request the
  TLS + HTTP/2 connection to YouTube stays pooled, so later resolutions skip the
  handshake. `prewarm()` pays that cost once at startup, before anyone is waiting.
- **TTL cache** of resolved streams, keyed by video id, evicted before the CDN URL
  expires — so a hit always returns a URL that still works.
- **Audio-only, Opus-preferred format selection** — Opus is Discord's native codec
  (cheapest transcode), and audio-only means ffmpeg never downloads video bytes.
- **Stream probe** — a 1-byte ranged GET confirms the CDN URL is live before it ever
  reaches ffmpeg, so a dead URL triggers a fallback instead of silent no-audio.

## When YouTube breaks the fast path

YouTube changes its anti-bot measures regularly. When in-process extraction starts
failing, the bot **keeps playing audio** — the yt-dlp fallback covers everything the
fast path cannot.

To restore the fast path, the fix is almost always in one file:
[`InnertubeClients.java`](src/main/java/renzuy/youtube/innertube/InnertubeClients.java)
— bump a `clientVersion` (and its User-Agent) to a current value, reorder
`PLAYER_ROTATION`, or drop a client that started returning ciphered URLs.

This library deliberately **never runs YouTube's player JavaScript** to decipher
signatures: the player rotation only uses clients that return direct stream URLs.
Anything ciphered is skipped and left to the fallback.

## Layout

```
renzuy/youtube/
├── YoutubeSource.java         Facade — the only entry point a consumer needs
├── YoutubeSourceOptions.java  Tuning (timeouts, cache, fallback)
├── AudioReference.java        The resolved, playable result
├── query/                    Query classification (URL vs. search), no I/O
├── innertube/                The in-process fast path + client roster
├── format/                   Audio-only / Opus-preferred format selection
├── cache/                    TTL stream cache
└── fallback/                 yt-dlp subprocess fallback
```

## Build & test

```bash
./gradlew :youtube-source:test
```
