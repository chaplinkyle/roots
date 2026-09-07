#!/usr/bin/env python3
"""Submit one durable command using only Python's standard library."""
import argparse
import base64
import hashlib
import hmac
import http.client
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, response, code, message, headers, new_url):
        # Credentials belong to the explicitly configured endpoint.
        return None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("operation", choices=("refresh-catalog", "reindex"))
    parser.add_argument("--url", default="http://127.0.0.1:8080")
    parser.add_argument("--id", help="Reuse this UUID when resuming an interrupted submission")
    parser.add_argument("--webhook", action="store_true", help="Authenticate using Standard Webhooks instead of Bearer")
    args = parser.parse_args()
    command_id = str(uuid.UUID(args.id)) if args.id else str(uuid.uuid4())
    base = args.url.rstrip("/")
    parsed = urllib.parse.urlsplit(base)
    if parsed.scheme not in ("http", "https") or not parsed.netloc or parsed.query or parsed.fragment or parsed.username is not None:
        parser.error("Use an HTTP(S) application URL without credentials, a query, or a fragment")
    secret_name = "AUTOMATION_WEBHOOK_SECRET" if args.webhook else "AUTOMATION_TOKEN"
    secret = os.environ.get(secret_name)
    if not secret:
        parser.error("Set " + secret_name + " in the process environment")
    webhook_key = None
    if args.webhook:
        if not secret.startswith("whsec_"):
            parser.error("AUTOMATION_WEBHOOK_SECRET must begin with whsec_")
        try:
            webhook_key = base64.b64decode(secret[6:], validate=True)
        except ValueError:
            parser.error("AUTOMATION_WEBHOOK_SECRET must contain valid Base64")
        if not 24 <= len(webhook_key) <= 64:
            parser.error("The decoded webhook key must contain 24 to 64 bytes")
    body = urllib.parse.urlencode({"command_id": command_id, "operation": args.operation}).encode("utf-8")
    # Emit the operation identifier before the first network attempt so it can be saved.
    print("command_id=" + command_id, file=sys.stderr, flush=True)
    url = base + ("/api/webhooks" if args.webhook else "/api/commands")
    opener = urllib.request.build_opener(NoRedirect())
    for attempt in range(3):
        headers = {"Content-Type": "application/x-www-form-urlencoded", "Accept": "application/json"}
        if args.webhook:
            timestamp = str(int(time.time()))
            signed = (command_id + "." + timestamp + ".").encode("ascii") + body
            signature = base64.b64encode(hmac.new(webhook_key, signed, hashlib.sha256).digest()).decode("ascii")
            headers.update({"webhook-id": command_id, "webhook-timestamp": timestamp, "webhook-signature": "v1," + signature})
        else:
            headers.update({"Authorization": "Bearer " + secret, "Idempotency-Key": command_id})
        request = urllib.request.Request(url, data=body, headers=headers, method="POST")
        try:
            with opener.open(request, timeout=10) as response:
                payload = response.read(16_385)
                if len(payload) > 16_384:
                    raise ValueError("Oversized response")
                result = json.loads(payload.decode("utf-8"))
                if not isinstance(result, dict) or result.get("id") != command_id or result.get("accepted") is not True:
                    raise ValueError("Unexpected command response")
                print(json.dumps({"status": response.status,
                                  "replayed": response.headers.get("Idempotency-Replayed"), "result": result}))
                return 0
        except urllib.error.HTTPError as failure:
            # A 4xx requires a corrected request or a business-status check.
            # A transient 5xx may follow a commit; retain exactly the same ID/body/key.
            if failure.code < 500 or attempt == 2:
                print("HTTP " + str(failure.code) + ": " + failure.read(16_384).decode("utf-8", errors="replace"), file=sys.stderr)
                return 1
            failure.close()
        except (urllib.error.URLError, TimeoutError, ConnectionError, http.client.HTTPException, ValueError):
            if attempt == 2:
                print("Outcome not confirmed. Resume with --id " + command_id + " and the same operation.", file=sys.stderr)
                return 1
        time.sleep(0.25 * (2 ** attempt))
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
