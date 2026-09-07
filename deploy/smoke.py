"""Exercise the shipped enterprise DEMO through a proxy; mutates its in-memory counter.

python deploy/smoke.py --url http://localhost:8080 --expected-nodes 2
Uses only the Python standard library. Never point this demo probe at business APIs.
"""
import argparse
import http.cookiejar
import json
import urllib.parse
import urllib.request
from html.parser import HTMLParser


class Bindings(HTMLParser):
    def __init__(self):
        super().__init__()
        self.root = {}
        self.action = None

    def handle_starttag(self, tag, attrs):
        values = dict(attrs)
        if values.get("id") == "roots":
            self.root = values
        action = values.get("data-roots-on-click", "")
        if action.endswith(":approve"):
            self.action = action


def exercise(base):
    cookies = http.cookiejar.CookieJar()
    client = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cookies))
    with client.open(base + "/_roots/health", timeout=10) as health:
        assert health.status == 200
        assert not any(cookie.name == "ROOTS_SESSION" for cookie in cookies)
    with client.open(base + "/", timeout=10) as page:
        assert page.status == 200
        node = page.headers["X-Roots-Node"]
        document = page.read().decode("utf-8")
    bindings = Bindings()
    bindings.feed(document)
    assert bindings.action, "This probe requires the shipped enterprise approval-counter demo"
    root = bindings.root
    values = {
        "_view": root["data-roots-view"], "_csrf": root["data-roots-csrf"],
        "_protocol": root["data-roots-protocol"], "_event": "click", "_action": bindings.action,
    }
    revision = int(root["data-roots-revision"])
    for _ in range(3):
        request = urllib.request.Request(base + "/_roots/action",
                data=urllib.parse.urlencode(values).encode("utf-8"),
                headers={"Content-Type": "application/x-www-form-urlencoded", "Origin": base,
                         "Accept": "application/json", "X-Roots-Request": "action"})
        with client.open(request, timeout=10) as response:
            assert response.headers["X-Roots-Node"] == node, "Action reached a different owner"
            patch = json.load(response)
            revision += 1
            assert patch["view"] == values["_view"]
            assert patch["revision"] == revision
    query = urllib.parse.urlencode({"view": values["_view"], "csrf": values["_csrf"],
                                  "protocol": values["_protocol"]})
    with client.open(base + "/_roots/stream?" + query, timeout=10) as stream:
        assert stream.headers["Content-Type"].startswith("text/event-stream")
        assert stream.headers["X-Roots-Node"] == node, "SSE reached a different owner"
        for _ in range(12):
            line = stream.readline().decode("utf-8")
            if line.startswith("data: "):
                snapshot = json.loads(line[6:])
                assert snapshot["view"] == values["_view"]
                assert snapshot["revision"] == revision
                break
        else:
            raise AssertionError("Proxy did not stream the initial snapshot promptly")
    with client.open(urllib.request.Request(base + "/_roots/dispose",
            data=urllib.parse.urlencode(values).encode("utf-8"), headers={"Origin": base}), timeout=10) as response:
        assert response.status in (200, 204)
    return node


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", default="http://localhost:8080")
    parser.add_argument("--expected-nodes", type=int, default=2)
    args = parser.parse_args()
    nodes = {exercise(args.url.rstrip("/")) for _ in range(max(6, args.expected_nodes * 3))}
    assert len(nodes) == args.expected_nodes, f"Expected {args.expected_nodes} owners; saw {len(nodes)}"
    print(json.dumps({"verified_owners": len(nodes), "checks": ["readiness", "affinity",
                      "CSRF actions", "exact revisions", "SSE", "disposal"]}))
