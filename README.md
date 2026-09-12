# PROTO Android

WebView client for **proto.ffilt.us** (the PROTO agent-comms broker UI + API).
Bundled Cloudflare Access service token — scoped to proto.ffilt.us ONLY via its
dedicated Access app (browser fallback: Google login via `allow_me` policy).

- CF headers injected at every surface: WebView GET/HEAD via
  `shouldInterceptRequest` → OkHttp; webapp fetch/XHR (incl. POST bodies) via
  injected JS shim + `CfAccessJsBridge` (Jellyfin-android pattern).
- Broker WebSockets ride the WebView network stack (shim covers handshake).
- Token pair comes from repo secrets `CF_ACCESS_CLIENT_ID/SECRET`
  (pinned — CI only re-uses, never rotates). Telemetry: standard ingest.
- CI: Gitea Actions on the `android` runner → release-signed → apps.ffilt.us store.
