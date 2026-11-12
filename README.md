# CineAnime ⚡

A portable, zero-backend static Progressive Web App (PWA) & Android TV WebView shell for tracking Anime, Movies, and TV Series progress with Stremio Addon protocol integration and end-to-end encrypted cross-device sync.

> **Architecture Note:** CineAnime hosts, stores, caches, and distributes **zero** copyrighted video or media files. Catalog metadata is fetched directly by your browser from public APIs (AniList, Kitsu, Cinemeta, AniSkip), and streams come only from user-installed Stremio add-ons or user-configured external URLs.

## Key Capabilities
- **Stremio Addon Protocol & Direct HLS Player:** Connect any compatible `manifest.json`, play `.m3u8`/`.mp4` streams via `hls.js` with resolution selection, Picture-in-Picture, OpenSubtitles lookup, local `.srt`/`.vtt` upload, and timing offset controls.
- **Metadata & Schedule Tracking:** Real-time airing countdowns, Watchlist status folders (`Watching`, `Plan to Watch`, `Completed`, `Dropped`), Drop Alerts, Anime Filler tags, and automatic metadata API failover (Kitsu + AniList + local snapshot cache).
- **Zero-Knowledge Cross-Device Sync & Portable Backups:** Sync Watch History, Episode Progress, Watchlist, Add-ons, and Source Overrides via AES-GCM encrypted Firebase RTDB / GitHub Gist / Cloudflare Worker, QR code, or 1-click full `.json` backup export/import.
- **Android TV Auto-Failover Shell:** D-pad spatial navigation, remote media keys, and multi-mirror failover with custom URL override (`android-tv/`).

## Mirroring & Self-Hosting
CineAnime uses strictly relative paths (`./index.html`, `./sw.js`, `./manifest.webmanifest`) so it runs identically on any static host without changes:
- **GitHub Pages / Codeberg Pages / Cloudflare Pages / Netlify:** Deploy the root directory directly as static assets (no build step required for the web app).
- **Android TV Shell (`android-tv/`):** Supports multiple backup origins and user-defined mirror URLs directly on TV if a primary host is ever unreachable.
