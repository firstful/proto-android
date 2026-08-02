# Talaria + APK Build Pipeline — HANDOFF (2026-08-02, UPDATED — GREEN)

**Status: talaria server LIVE; APK build pipeline GREEN end-to-end (run 95, 07:09 UTC):
signed APK built on the k3s runner and published to the Sutliff Apps store.**

---

## TL;DR

| Piece | State |
|---|---|
| talaria voice gateway (server) | ✅ Deployed in k3s ns `talaria`, pod Running on reed-a320ms2h, `/health` OK (AVX-pinned) |
| App store backend | ✅ Deployed ns `appstore`, `https://apps.ffilt.us/health` OK, catalog serves the talaria APK |
| APK build pipeline (CI) | ✅ **GREEN — run 95 success** (SDK 36, AGP 8.9.2, Gradle 8.11.1, signed, published HTTP 200) |
| Sutliff Apps store Android client | ❌ Planned, not started |
| Access app for `apps.ffilt.us` / `talaria.ffilt.us` | ⚠️ talaria has a non_identity service-token app (for the APK's WAN path); `apps.ffilt.us` Google-login app still pending (needs family emails) |

**Runner location (2026-08-02): wyse5070.** reed-a320ms2h has a BROKEN layer-2 link
(1.3s RTT to the router, ~0 B/s egress to Cloudflare) — see infra notes below. The
runner + its Longhorn storage were moved off it.

**Parallel-work warning:** a sibling session was building the same pipeline concurrently.
The remote `reeds/talaria` `origin/main` now contains THEIR commits
(`bbc0f4d` "pilot: APK build pipeline…" → `b5edbd8` "ci: install pyyaml in job") —
manual-clone workaround, CF_ACCOUNT_ID handling, workflow consolidation, pyyaml-install
step. **The local clone at /srv/talaria is aligned to `origin/main` (b5edbd8).** Do not
re-apply older local edits; build on the remote state. Their approach == the design below.

---

## The blocker that WAS — CF 9106 + a chain of real issues (all fixed by run 95)

The 9106 was just the first in a chain. What was actually wrong and fixed:

1. **CF_MINT_TOKEN** — the stored value was base64'd (Gitea stores the `data` field
   AS-IS; base64 is only for secrets the consumer decodes) and the original token was
   WAN-IP-bound (pod egress ≠ the bound IP). Fixed: re-minted `cf-mint-ci` from the
   vault master (Access apps+policies Write, Service Tokens Write, Account Settings
   Read, account-scoped, NO IP restriction), stored RAW in the repo secret + vault.
2. **IPv6/DNS egress** — CF calls now go through `curl -4` with the resolved IP pinned
   via `--resolve` + retries (script `ci-ensure-access-token.py`). Pod DNS was
   kube-dns with ndots:5 ≈ 2s/lookup; the runner deployment now uses
   `dnsPolicy: Default` (node resolver = OPNsense).
3. **Policy rule bug** — Access policy `include.service_token.token_id` must be the
   token's UUID (`id`), not `client_id`. Also match policies by name to avoid dupes.
4. **SDK version** — project was SDK 34; the runner image ships 36/37. Upgraded to
   compileSdk/targetSdk 36, buildTools 36.0.0, AGP 8.9.2, Gradle 8.11.1,
   `android.useAndroidX=true` (AGP 8.9 hard-fails without it).
5. **Build stalls** — pod egress crawled (~400KB/s) + a 1.3s-RTT layer-2 link on
   reed-a320ms2h. The runner moved to **wyse5070** (20MB/s egress). Longhorn replica
   on the broken node was also stalling volume I/O: cache volumes cut to 1 replica on
   wyse5070, worker marked Longhorn-unschedulable. Gradle gets HTTP timeouts +
   retries via `gradle.properties`; `gradle-cache` docker volume (in the DinD) persists
   the gradle home across jobs.
6. **Publish 422** — the store's UploadFile field is `payload`; the script sent
   `file=`. Fixed (`-F payload=@…`).
7. **Runner restarts** — the runner's PVCs are RWO; a rollout restart deadlocks (new
   pod can't mount until the old dies). Scale to 0 → 1, or delete the old pod first.

**Live state: run 95 GREEN** — BUILD SUCCESSFUL 17m38s, V2 signature
CN=Sutliff Homelab verified, publish HTTP 200, catalog + OTA + APK download verified
at apps.ffilt.us.

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

## Next steps

1. ✅ ~~Fix the 9106~~ — pipeline GREEN (run 95). Re-run = fast (gradle-cache volume warm).
2. **Fix reed-a320ms2h's link** — 1.3s RTT to the router, ~0 B/s egress. Hardware
   suspect: USB NIC (`enxb42e99e9863a`) / cable / switch port. It's also a Longhorn
   replica host (marked unschedulable 2026-08-02) — repair the link, then re-enable.
3. **Access app for `apps.ffilt.us`** (Google login, family emails) so the store works
   externally; `talaria.ffilt.us` already has its non_identity service-token app.
4. **Wire real versioning** — `ci-publish-apk.py` hardcodes `version: "1"`; the gradle
   `printVersionName` task exists but isn't called by the workflow. The store's OTA
   manifest should carry the real versionName.
5. Build the **Sutliff Apps** store Android client (catalog + OTA updates).
6. Guest onboarding: per-guest keystore (`Android Keystore - <guest>`) + `publisher`
   field wiring.
