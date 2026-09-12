#!/usr/bin/env python3
"""ci-publish-apk.py <apk_path> — publish a built APK to the Sutliff Apps store.

Reads app-manifest.yaml (apk.storeDir, apk.package), POSTs the APK and a version
manifest to the store backend. Publish auth via APPSTORE_PUBLISH_TOKEN (repo secret).
"""
import json
import os
import subprocess
import sys
import yaml
import datetime

STORE = os.environ.get("APPSTORE_URL", "http://apps.ffilt.us")


def post(path, headers, files=None, data=None):
    cmd = ["curl", "-s", "-o", "/dev/null", "-w", "%{http_code}", "-X", "POST",
           "-H", f"X-Publish-Token: {os.environ['APPSTORE_PUBLISH_TOKEN']}"] + \
          [h for kv in headers.items() for h in ("-H", f"{kv[0]}: {kv[1]}")]
    if files:
        cmd += ["-F", f"payload=@{files}"]
    if data:
        cmd += ["-F", f"manifest={json.dumps(data)}"]
    cmd.append(STORE + path)
    r = subprocess.run(cmd, capture_output=True, text=True)
    return r.stdout.strip()


def main():
    apk = sys.argv[1]
    m = yaml.safe_load(open("app-manifest.yaml"))
    apk_cfg = m["apk"]
    pkg = apk_cfg["package"]
    store_dir = apk_cfg["storeDir"]

    size = os.path.getsize(apk)
    now = datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds")
    manifest = {
        "app": pkg,
        "name": m.get("name", pkg).replace("-", " ").title(),
        "version": "1",
        "apk": f"{store_dir}/{os.path.basename(apk)}",
        "size": size,
        "built": now,
    }
    code = post(f"/publish/{store_dir}/{os.path.basename(apk)}", {}, files=apk, data=manifest)
    print(f"publish -> {STORE}/publish/{store_dir}/{os.path.basename(apk)} (http {code})")
    if not code.startswith("2"):
        sys.exit(f"publish failed: http {code}")


if __name__ == "__main__":
    main()
