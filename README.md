# StreamEntity — Build & Publish Package

Complete production source of the StreamEntity Sequential Streaming Engine
frontend, with a zero-dependency Node server starter. No demo / simulated
mode is included — every number on the page comes from live telemetry.

## Contents

| File | What it is |
|---|---|
| `index.html` | The complete frontend: magnet input, video player (play/pause, ±10s seek, volume, PiP, fullscreen, audio/subtitle track menus, copy stream URL, VLC handoff), Live Swarm (Network / Radar / Timeline tabs), FUSE buffer, Active Streams + piece maps, peer diagnostics, stream history (last 10, session-only), donations panel. |
| `assets/worldmap.js` | World map data for the Radar tab: Natural Earth country polygons as one path (`WORLD_MAP_PATH`) in 1000×500 equirectangular space. |
| `assets/streamentity-publish.zip` | This same package, as served by the published page's download button (only present in the web build). |
| `server.js` | Zero-dependency Node static server + the API surface the frontend expects, with TODO markers where a torrent engine plugs in. |
| `package.json` | `npm start` runs `node server.js`. Node ≥ 18, no dependencies. |

## Publish the frontend only (static hosting)

The frontend is fully static — upload `index.html` and the `assets/`
folder as-is to any static host (Netlify, Vercel, GitHub Pages, S3,
nginx, etc.), keeping the folder structure:

```
index.html
assets/worldmap.js
assets/streamentity-publish.zip   (optional — powers the in-page download button)
```

Without a streaming server, the page loads and the server-status card
will report that the streaming server can't be reached.

## Run with the Node starter

```bash
npm start
# → http://localhost:3000
```

`server.js` serves the frontend and answers `GET /api/torrents` with an
empty list. Real streaming needs a torrent engine wired into the TODO
handlers (see the Streaming API contract below).

## Streaming API contract (what the frontend expects)

All endpoints are same-origin with the page:

- `POST /api/torrents/add` — body `{ "torrentId": "<magnet link or infohash>" }`
  → `{ "success": true, "torrent": { "infoHash", "name", "cleanTitle", "activeFileName" } }`.
  The player then loads `GET /stream/<infoHash>`.
- `GET /stream/<infoHash>` — the active file's bytes, with HTTP Range
  support so the player can seek.
- `GET /api/torrents` — JSON array of live torrents (shape documented in
  `server.js`).
- `WS /ws` — WebSocket pushing ~1 message/sec while active:
  `{ "type": "swarm_telemetry", "data": { "global": { "totalDownloadSpeed" },
  "activeTorrent": {...}, "torrents": [...], "clients": [...] } }`.
  Peer objects may carry `lat`/`lon` (or `country`) — the Radar plots
  peers at their real location when present.

## Notes

- Dark/light mode follows the in-page toggle; dark is the default.
- Stream history is session-only (in memory) — nothing is persisted.
- Donation links (Buy Me a Coffee / Revolut / UPI) are placeholders in
  the panel markup — add your own handles/links before publishing.
