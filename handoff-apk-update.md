# Talaria — Handoff (APK Build Integration)

**Date:** 2026-07-05
**Status:** Implementation complete — all code changes applied

---

## Goal

Integrate APK builds into agent-stack so repos can define an `apkBuild` block in their `agent-stack.json` and get auto-built APKs via `install.ffilt.us`.

---

## How It Works

The reconciliation loop calls `ensureApkBuild()` for every service with an `apkBuild` block in its `agent-stack.json`.

### Pipeline

```
git push -> ref mtime changes -> reconcile tick
  -> commit hash changed?
    -> content hash changed?
      -> yes: ensureServiceClone() snapshots repo to /srv/clones/<name>/<hash>/
        -> setup-cf-creds.sh injects fresh CF Access creds into local.properties
        -> build-apk.sh runs from the clone directory
          -> ./gradlew assembleDebug
          -> APK deployed to /srv/data/files/apk/<destDir>/
          -> OTA version manifest written
        -> APK hash marker updated (/tmp/stack.<name>.apk-hash)
```

### Change Detection (Two-Step Guard)

1. **Fast**: compare git commit hash against `/tmp/stack.<name>.chash`
2. **Precise**: compare `.stackignore`-filtered content hash against `/tmp/stack.<name>.apk-hash`

### Shared Infrastructure Used

| Component | Path | Role |
|---|---|---|
| `setup-cf-creds.sh` | `/srv/agent-stack/scripts/setup-cf-creds.sh` | Injects CF Access credentials from `~/.hermes/cf-creds.properties` into each project's `local.properties` before every build |
| `ensureServiceClone()` | `infra/lib/services.ts` | Snapshots repo at current commit to `/srv/clones/<name>/<hash>/` |
| `build-apk.sh` | Per-repo (e.g. `repos/talaria/build-apk.sh`) | Gradle build + APK deploy + OTA manifest |

---

## Files Changed

| File | Change |
|---|---|
| `infra/lib/config.ts` | Added `apkHashMarker()` |
| `infra/lib/services.ts` | Fixed `findApkBuildScript()` (path shadowing bug) |
| `infra/lib/reconcile.ts` | Added `ensureApkBuild()` -- calls `ensureServiceClone`, `setup-cf-creds.sh`, then `build-apk.sh` |
| `infra/reconcile-worker.ts` | Added Step 3b APK build loop + import |
| `infra/lib/runtime.ts` | Added APK build state to `services.json` output |

---

## What ensureApkBuild Does NOT Do

- **Version manifest** -- `build-apk.sh` already writes its own to `version-com.reedsutliff.talaria.json`
- **APK deployment** -- `build-apk.sh` copies the APK to `/srv/data/files/apk/<app>/`
- **OTA manifest** -- `build-apk.sh` writes the version manifest consumed by `install.ffilt.us`

These are the build script's responsibility, not the controller's.

---

## Talaria agent-stack.json

Already has the `apkBuild` block:

```json
{
  "apkBuild": {
    "packageName": "com.reedsutliff.talaria",
    "script": "build-apk.sh",
    "destDir": "Talaria"
  }
}
```

---

## Next Steps

1. Restart the controller to pick up new code (auto on next git ref change)
2. Make a commit to talaria repo and verify `[apk]` messages in controller logs
3. Verify OTA endpoint: `curl https://install.ffilt.us/update?app=com.reedsutliff.talaria&ver=0`
