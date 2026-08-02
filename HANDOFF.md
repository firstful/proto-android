# Talaria + APK Build Pipeline — HANDOFF (2026-08-02)

**Status: talaria server LIVE and healthy; APK build pipeline WIRED but NOT green
(blocked on CF token step, error 9106 — see below).**

---

## TL;DR

| Piece | State |
|---|---|
| talaria voice gateway (server) | ✅ Deployed in k3s ns `talaria`, pod Running on reed-a320ms2h, `/health` OK (stt_loaded, hermes_healthy), LAN split-DNS 200 |
| App store backend | ✅ Deployed ns `appstore`, `https://apps.ffilt.us/health` OK, `/apps.json` empty catalog |
| APK build pipeline (CI) | ⚠️ Wired (runner android label, workflow, secrets, signing) — last run **66 failed** at the CF token step |
| Sutliff Apps store Android client | ❌ Planned, not started |
| Access app for `apps.ffilt.us` / `talaria.ffilt.us` | ❌ Pending (external hits 302 at Access; LAN bypasses) |

**Parallel-work warning:** a sibling session was building the same pipeline concurrently.
The remote `reeds/talaria` `origin/main` now contains THEIR commits
(`bbc0f4d` "pilot: APK build pipeline…" → `b5edbd8` "ci: install pyyaml in job") —
manual-clone workaround, CF_ACCOUNT_ID handling, workflow consolidation, pyyaml-install
step. **The local clone at /srv/talaria is aligned to `origin/main` (b5edbd8).** Do not
re-apply older local edits; build on the remote state. Their approach == the design below.

---

## The current blocker — CF 9106 in the token step

**Symptom (run 66, job 72, step "Ensure Cloudflare Access token"):**
```
CF API GET /accounts/224469ecf8e8a619106fe8ffa19c7a43/access/service_tokens:
400 {"code":9106,"message":"Authentication failed (status: 400)"}
```

**Facts established:**
- The secret IS injected — job log shows `CF_MINT_TOKEN: ***` (Gitea masks non-empty secrets).
- The token value originally stored (`cfut_mwIC…add6`, minted from the vault master as
  `cf-mint-ci`, 1y TTL, WAN-IP-bound to 71.74.85.232/32) **verifies OK from the control
  plane with `curl -4`**.
- The workflow's script (`scripts/ci-ensure-access-token.py`, sibling version) uses
  **urllib, not curl** — it does NOT force IPv4. The box has native IPv6 egress; an
  IPv6-sourced request to an IPv4-bound token gives CF error 9109 ("from location: <ipv6>"),
  and empty/mangled headers give 9106. **IPv6 egress is the prime suspect.**
- The sibling commit mentions minting `cf-mint-ci` — **the stored CF_MINT_TOKEN value may
  have been overwritten** (secrets are write-only; can't read back to compare).

**Next diagnostic steps (in order):**
1. Test the known token from a pod pinned to reed-a320ms2h (busybox `wget` with
   `--header="Authorization: Bearer …"`) vs the control plane. If it fails from the node
   but works from the CP → egress/IP issue, not the token.
2. Check the node's egress: `wget -qO- https://api.cloudflare.com/cdn-cgi/trace | grep ^ip=`
   from a pod on reed-a320ms2h — is the source the WAN IPv4 (71.74.85.232) or an IPv6?
3. If IPv6 is the source → force IPv4 in the script (urllib doesn't force it; either
   `curl -4` via subprocess or set the socket family) and re-run. This is the same
   `curl -4` gotcha documented in the `cloudflare-token-minting` skill.
4. If the token itself fails everywhere → the secret was overwritten; re-mint via
   `cf-mint-token mint access-service-tokens 31536000 cf-mint-ci` and PUT it back
   (Gitea API: `PUT /api/v1/repos/reeds/talaria/actions/secrets/CF_MINT_TOKEN`).

---

## Architecture (locked)

**Token flow:** `app-manifest.yaml` declares `subdomains[0].hostname` → workflow step
`ci-ensure-access-token.py <subdomain>` mints/ensures an Access service token
(name `ci-<subdomain>`; rotates if exists), ensures the Access app + `non_identity`
policy (decision MUST be `non_identity`; `allow` → 302), best-effort saves the pair as
repo secrets `CF_ACCESS_CLIENT_ID/SECRET`, prints `KEY=VALUE` for `$GITHUB_ENV` →
BuildConfig fields.

**Signing:** keystore vaulted (`Android Release Keystore (APK signing)`, JKS attachment)
→ `sync-apk-signing-secret.sh <repo> [publisher]` deploys
`ANDROID_KEYSTORE_B64/PASSWORD/ALIAS/KEY_PASSWORD` repo secrets → workflow decodes →
`build.gradle.kts` release signingConfig → `assembleRelease`. Per-publisher keys
(guests get `Android Keystore - <guest>`).

**Build worker:** reed-a320ms2h labeled `rook.io/avx=true` + `rook.io/build=true`.
Runner label `android:docker://cimg/android:2026.07-node` set via **GITEA_RUNNER_LABELS
env** (v3.0.0 ignores `labels:` in config) + **CONFIG_FILE=/data/config.yaml** env
(run.sh only reads config when CONFIG_FILE is set). Job runs `--user root`. CI is
best-effort (no HA) — Reed's call.

**Store:** `reeds/appstore` (FastAPI, `python-multipart` REQUIRED), image
`192.168.1.2:3000/reeds/appstore:2`, ns `appstore`, NFS PVC. Endpoints:
`/apps.json`, `/apk/<dir>/<file>`, `/version/<pkg>.json`, `/publish` (X-Publish-Token).
Publish token: k8s secret `appstore-publish-token` + Gitea secret `APPSTORE_PUBLISH_TOKEN`.

---

## Secrets inventory (Gitea repo secrets on reeds/talaria)

| Secret | Source | Purpose |
|---|---|---|
| `CF_MINT_TOKEN` | minted from vault master (cf-mint-ci) | mint per-subdomain Access tokens in CI |
| `CF_ACCESS_CLIENT_ID/SECRET` | created by pipeline on first build | the APK's embedded token |
| `ANDROID_KEYSTORE_B64/PASSWORD/ALIAS/KEY_PASSWORD` | vault sync helper | release signing |
| `APPSTORE_PUBLISH_TOKEN` | openssl rand | store publish auth |

## Where things live

- `/srv/talaria` — app repo clone (aligned to origin/main b5edbd8)
- `/srv/appstore` — store backend repo
- `/tmp/sutliff-cluster` — cluster GitOps repo clone
- `/root/homelab-cluster-spec.md` — living cluster spec (talaria + CP policy sections)
- Vault `/home/eizooz/homelab-creds.kdbx` — master CF token, keystore entry
- Skills: `cloudflare-token-minting`, `test-wan-path`, `k3s-homelab-cluster`,
  `docs-sync` (homelab-docs: `architecture/apk-build-pipeline.md`, `ops/talaria.md`)

## Key gotchas (each cost real time)

1. **`curl -4` ALWAYS for CF API** — native IPv6 egress → 9109/9106 on WAN-IP-bound tokens.
2. **Runner v3.0.0:** labels go in `GITEA_RUNNER_LABELS` env, NOT config.yaml; config only
   read when `CONFIG_FILE` is set.
3. **SDK images:** thyrlian/android-sdk lacks **node** → actions/checkout exit 127. Use
   `cimg/android:2026.07-node` (+ `--user root`; SDK 36/37 preinstalled, AGP auto-downloads 34).
4. **cimg has no pyyaml** — install step or the mini parser (`scripts/manifest.py` was
   written then abandoned when aligning to the sibling's pyyaml-install approach).
5. **Registry push:** the Gitea cluster token lacks `write:package` — docker push works
   via the pre-existing docker login creds on the CP.
6. **Gitea secrets:** repo-level only (no org/user), values write-only (can't read back).
7. **Non-AVX SIGILL (exit 132):** ctranslate2 needs AVX; only reed-a320ms2h can run
   talaria (nodeSelector rook.io/avx=true).

## Next steps (owner: whoever picks this up)

1. **Fix the 9106** (diagnostic ladder above), get a green run.
2. Verify the produced APK: `apksigner verify --print-certs` (signed with sutliff key),
   `CF_ACCESS_CLIENT_ID` in BuildConfig, APK + manifest landed on the store
   (`/apps.json` + `/apk/Talaria/talaria-*.apk`).
3. Add Access app for `apps.ffilt.us` (Google login, family emails) so the store works
   externally; `talaria.ffilt.us` service-token app for the APK's WAN path.
4. Build the **Sutliff Apps** store Android client (catalog + OTA updates) — first app
   to ride the pipeline.
5. Guest onboarding: per-guest keystore (`Android Keystore - <guest>`) + `publisher`
   field wiring.
