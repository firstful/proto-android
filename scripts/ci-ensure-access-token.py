#!/usr/bin/env python3
"""Ensure a CF Access service token exists for a subdomain and expose it to the build.

Usage: ci-ensure-access-token.py <subdomain>

Behavior:
  1. If CF_ACCESS_CLIENT_ID / CF_ACCESS_CLIENT_SECRET are already set (injected from
     repo secrets by the workflow), just re-export them.
  2. Else mint with CF_MINT_TOKEN:
       - find-or-create the Access service token (name "ci-<subdomain>"; if one exists
         but we lost the secret, ROTATE it to get a fresh client_secret)
       - find-or-create the Access app for the subdomain (self_hosted)
       - find-or-create a NON_IDENTITY policy on that app gated by the service token
         (decision non_identity; "allow" would 302)
       - best-effort PUT the pair as repo secrets for next time
  3. Print CF_ACCESS_CLIENT_ID=... / CF_ACCESS_CLIENT_SECRET=... for $GITHUB_ENV.

Exit 0 = env lines printed. Exit 1 = hard failure.
"""
import json
import os
import socket
import subprocess
import sys
import time

CF = "https://api.cloudflare.com/client/v4"
GITEA = os.environ.get("GITEA_URL", "http://192.168.1.2:3000")

SUB = sys.argv[1] if len(sys.argv) > 1 else os.environ.get("CF_ACCESS_SUBDOMAIN", "")
if not SUB:
    sys.exit("usage: ci-ensure-access-token.py <subdomain>")

existing_id = os.environ.get("CF_ACCESS_CLIENT_ID", "")
existing_secret = os.environ.get("CF_ACCESS_CLIENT_SECRET", "")

if existing_id and existing_secret:
    print(f"CF_ACCESS_CLIENT_ID={existing_id}")
    print(f"CF_ACCESS_CLIENT_SECRET={existing_secret}")
    sys.exit(0)

mint = os.environ.get("CF_MINT_TOKEN", "")
if not mint:
    sys.exit("CF_ACCESS_CLIENT_ID/SECRET missing and no CF_MINT_TOKEN to mint one")


def resolve_cf():
    """Resolve api.cloudflare.com IPv4 once — node DNS flakes intermittently."""
    for attempt in range(10):
        try:
            return socket.getaddrinfo("api.cloudflare.com", 443, socket.AF_INET)[0][4][0]
        except socket.gaierror:
            time.sleep(3)
    raise SystemExit("could not resolve api.cloudflare.com (node DNS flaky)")


CF_IP = resolve_cf()


def cf(path, method="GET", body=None):
    """CF API call via curl -4 pinned to the resolved IPv4 (DNS + IPv6-proof). Retry 5x."""
    args = ["curl", "-4", "-sS", "-X", method, CF + path,
            "--resolve", f"api.cloudflare.com:443:{CF_IP}",
            "-H", f"Authorization: Bearer {mint}", "-H", "Content-Type: application/json",
            "--connect-timeout", "15", "--max-time", "45"]
    if body is not None:
        args += ["-d", json.dumps(body)]
    last = None
    for attempt in range(5):
        r = subprocess.run(args, capture_output=True, text=True, timeout=60)
        if r.returncode == 0 and r.stdout.strip():
            try:
                d = json.loads(r.stdout)
            except json.JSONDecodeError:
                last = f"non-JSON response: {r.stdout[:200]}"
            else:
                if not d.get("success"):
                    raise SystemExit(f"CF API {method} {path}: {d.get('errors')}")
                return d
        else:
            last = f"curl exit {r.returncode}: {r.stderr[:200]}"
        time.sleep(5 * (attempt + 1))
    raise SystemExit(f"CF API {method} {path} failed after 5 attempts: {last}")


try:
    if os.environ.get("CF_ACCOUNT_ID"):
        acc = os.environ["CF_ACCOUNT_ID"]
    else:
        accts = cf("/accounts")["result"]
        if not accts:
            sys.exit("CF_MINT_TOKEN sees no accounts (set CF_ACCOUNT_ID to skip account listing)")
        acc = accts[0]["id"]
except SystemExit as e:
    sys.exit(f"CF_MINT_TOKEN cannot list accounts: {e}")

# --- service token: find by name, else create; if exists but secret lost, rotate ---
toks = cf(f"/accounts/{acc}/access/service_tokens")["result"]
tok = next((t for t in toks if t.get("name") == f"ci-{SUB}"), None)
if tok:
    tok_id = tok["id"]
    rot = cf(f"/accounts/{acc}/access/service_tokens/{tok['id']}/rotate", "POST")
    new_id, new_secret = rot["result"]["client_id"], rot["result"]["client_secret"]
else:
    st = cf(f"/accounts/{acc}/access/service_tokens", "POST",
            {"name": f"ci-{SUB}", "type": "service_token"})["result"]
    tok_id = st["id"]
    new_id, new_secret = st["client_id"], st["client_secret"]

# --- Access app: find by domain, else create ---
apps = cf(f"/accounts/{acc}/access/apps")["result"]
app = next((a for a in apps if a.get("domain") == SUB), None)
if not app:
    app = cf(f"/accounts/{acc}/access/apps", "POST",
             {"name": f"CI {SUB}", "domain": SUB, "type": "self_hosted",
              "app_domain": f"https://{SUB}"})["result"]
app_id = app["id"]

# --- non_identity policy gated by the service token (decision must be non_identity) ---
pols = cf(f"/accounts/{acc}/access/policies")["result"]
pol = next((p for p in pols if p.get("app_id") == app_id and p.get("decision") == "non_identity"), None)
if not pol:
    cf(f"/accounts/{acc}/access/policies", "POST",
       {"name": f"CI {SUB} service token", "decision": "non_identity",
        "include": [{"service_token": {"token_id": tok_id}}], "app_id": app_id})

# --- best-effort: save repo secrets for next time ---
# Gitea stores the "data" field AS-IS (no base64 decode); raw values only.
# (base64 in "data" is only for secrets the consumer decodes itself, e.g.
# ANDROID_KEYSTORE_B64.)
repo = os.environ.get("GITHUB_REPOSITORY", "")
gtok = os.environ.get("GITEA_TOKEN", "") or os.environ.get("GITHUB_TOKEN", "")
if repo and gtok:
    base = f"{GITEA}/api/v1/repos/{repo}/actions/secrets/"
    for name, val in (("CF_ACCESS_CLIENT_ID", new_id), ("CF_ACCESS_CLIENT_SECRET", new_secret)):
        r = subprocess.run(
            ["curl", "-sS", "-X", "PUT", base + name,
             "-H", f"Authorization: token {gtok}", "-H", "Content-Type: application/json",
             "-d", json.dumps({"data": val})],
            capture_output=True, text=True, timeout=30)
        if r.returncode != 0:
            print(f"::warning::could not save repo secret {name}: {r.stderr[:200]}")

print(f"CF_ACCESS_CLIENT_ID={new_id}")
print(f"CF_ACCESS_CLIENT_SECRET={new_secret}")
