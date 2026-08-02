#!/usr/bin/env python3
"""ci-ensure-access-token.py <subdomain> — make a Cloudflare Access service token
available to this build for the declared subdomain.

If repo secrets CF_ACCESS_CLIENT_ID/SECRET already exist, the runner injects them
as env and this script just re-exports them for the job. Otherwise it mints a
service token (via CF_MINT_TOKEN, scoped access-service-tokens), ensures the
Access app + non_identity policy exist, stores the pair as repo secrets (so future
builds reuse), and exports them.

Env: CF_MINT_TOKEN (repo secret), GITHUB_TOKEN (runner), GITHUB_REPOSITORY,
CF_ACCESS_CLIENT_ID / CF_ACCESS_CLIENT_SECRET (optional, if already provisioned).
"""
import json
import os
import subprocess
import sys

ACCT = "224469ecf8e8a619106fe8ffa19c7a43"
API = "https://api.cloudflare.com/client/v4"
GITEA = "http://192.168.1.2:3000/api/v1"


def cf(method, path, data=None, token=None):
    token = token or os.environ["CF_MINT_TOKEN"]
    cmd = ["curl", "-4", "-s", "-X", method, "-H", f"Authorization: Bearer {token}"]
    if data is not None:
        cmd += ["-H", "Content-Type: application/json", "-d", json.dumps(data)]
    cmd.append(API + path)
    out = subprocess.run(cmd, capture_output=True, text=True).stdout
    return json.loads(out) if out.strip() else {"success": False}


def gitea_put_secret(name, value):
    repo = os.environ["GITHUB_REPOSITORY"]
    tok = os.environ["GITHUB_TOKEN"]
    r = subprocess.run(
        ["curl", "-s", "-o", "/dev/null", "-w", "%{http_code}", "-X", "PUT",
         "-H", "Authorization: token " + tok, "-H", "Content-Type: application/json",
         "-d", json.dumps({"data": value}),
         f"{GITEA}/repos/{repo}/actions/secrets/{name}"],
        capture_output=True, text=True)
    return r.stdout.strip()


def mask(s):
    return f"{s[:4]}...{s[-4:]}" if len(s) > 10 else "***"


def main():
    subdomain = sys.argv[1]
    # Already provisioned as repo secrets -> injected as env by the runner.
    if os.environ.get("CF_ACCESS_CLIENT_ID") and os.environ.get("CF_ACCESS_CLIENT_SECRET"):
        with open(os.environ["GITHUB_ENV"], "a") as f:
            f.write(f"CF_ACCESS_CLIENT_ID={os.environ['CF_ACCESS_CLIENT_ID']}\n")
            f.write(f"CF_ACCESS_CLIENT_SECRET={os.environ['CF_ACCESS_CLIENT_SECRET']}\n")
        print(f"using existing service token for {subdomain} "
              f"({mask(os.environ['CF_ACCESS_CLIENT_ID'])})")
        return

    label = subdomain.split(".")[0]
    tok_name = f"{label}-apk"

    # 1. Access app
    apps = cf("GET", f"/accounts/{ACCT}/access/apps")
    app = next((a for a in apps.get("result", []) if a.get("domain") == subdomain), None)
    if not app:
        r = cf("POST", f"/accounts/{ACCT}/access/apps",
               {"name": subdomain, "domain": subdomain, "type": "self_hosted",
                "session_duration": "24h"})
        if not r.get("success"):
            sys.exit(f"create Access app failed: {r.get('errors')}")
        app = r["result"]
        print(f"created Access app {subdomain} ({app['id']})")
    else:
        print(f"Access app exists {subdomain} ({app['id']})")
    app_id = app["id"]

    # 2. Service token (find by name; create if missing)
    sts = cf("GET", f"/accounts/{ACCT}/access/service_tokens")
    st = next((t for t in sts.get("result", []) if t.get("name") == tok_name), None)
    if st:
        print(f"service token exists {tok_name} ({st['id']}) — but client_secret unknown, "
              "revoke it or reuse via repo secrets; minting fresh instead")
        r = cf("POST", f"/accounts/{ACCT}/access/service_tokens", {"name": tok_name})
    else:
        r = cf("POST", f"/accounts/{ACCT}/access/service_tokens", {"name": tok_name})
    if not r.get("success"):
        sys.exit(f"create service token failed: {r.get('errors')}")
    st = r["result"]
    st_id, st_cid, st_cs = st["id"], st["client_id"], st["client_secret"]
    print(f"minted service token {tok_name} ({mask(st_cid)})")

    # 3. non_identity policy (mirrors the proven pattern; allow -> 302)
    pols = cf("GET", f"/accounts/{ACCT}/access/apps/{app_id}/policies")
    existing = any(
        inc.get("service_token", {}).get("token_id") == st_id
        for p in pols.get("result", []) for inc in p.get("include", []))
    if not existing:
        prec = max([p.get("precedence") or 0 for p in pols.get("result", [])] or [0]) + 1
        r = cf("POST", f"/accounts/{ACCT}/access/apps/{app_id}/policies",
               {"name": f"{tok_name}-token", "decision": "non_identity",
                "include": [{"service_token": {"token_id": st_id}}], "precedence": prec})
        if not r.get("success"):
            sys.exit(f"create policy failed: {r.get('errors')}")
        print(f"added non_identity policy (precedence {prec})")

    # 4. Store as repo secrets for future builds
    h1 = gitea_put_secret("CF_ACCESS_CLIENT_ID", st_cid)
    h2 = gitea_put_secret("CF_ACCESS_CLIENT_SECRET", st_cs)
    print(f"stored repo secrets (http {h1}/{h2})")

    # 5. Export for this build
    with open(os.environ["GITHUB_ENV"], "a") as f:
        f.write(f"CF_ACCESS_CLIENT_ID={st_cid}\n")
        f.write(f"CF_ACCESS_CLIENT_SECRET={st_cs}\n")
    print(f"provisioned service token for {subdomain} ({mask(st_cid)})")


if __name__ == "__main__":
    main()
