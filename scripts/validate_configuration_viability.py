#!/usr/bin/env python3
"""Smoke test for report-only Configuration Viability over the REST API."""

import argparse
import json
import sys
import time
from urllib.request import Request, urlopen
from urllib.error import HTTPError, URLError


def call(base_url, method, path, payload=None, timeout=120):
    data = None if payload is None else json.dumps(payload).encode("utf-8")
    request = Request(base_url.rstrip("/") + path, data=data, method=method)
    request.add_header("Content-Type", "application/json")
    with urlopen(request, timeout=timeout) as response:
        return json.loads(response.read().decode("utf-8"))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://127.0.0.1:8088")
    parser.add_argument("--devices", type=int, default=28)
    args = parser.parse_args()
    try:
        call(args.base_url, "POST", "/reset", {
            "devicesCount": args.devices,
            "algorithmIndex": 0,
            "architectureIndex": 0,
            "seed": 42,
            "scenarioProfile": "default",
            "taskSourceMode": "current",
            "actionMaskMode": "visible_only",
            "simulationTimeMinutes": 30,
            "waitForFirstDecision": False,
        })
        report = None
        deadline = time.time() + 60.0
        while time.time() < deadline:
            report = call(args.base_url, "GET", "/configuration/viability")
            if report.get("candidates") or report.get("status") in ("FINISHED", "FAILED"):
                break
            time.sleep(0.25)
        if report is None or "candidates" not in report or not report["candidates"]:
            raise RuntimeError("viability report is unavailable before a decision state was built")
        if report.get("mode") != "report_only":
            raise RuntimeError("unexpected viability mode: %s" % report.get("mode"))
        valid = {"VIABLE", "INVIABLE", "UNCERTAIN"}
        statuses = [item.get("viabilityStatus") for item in report["candidates"]]
        if any(status not in valid for status in statuses if status is not None):
            raise RuntimeError("invalid viability status in report")
        print("viability mode=%s" % report.get("mode"))
        print("candidates=%d viable=%d inviable=%d uncertain=%d" % (
            len(report["candidates"]), report.get("viableCandidateCount", 0),
            report.get("inviableCandidateCount", 0), report.get("uncertainCandidateCount", 0)))
        print("configuration viability: PASS")
        return 0
    except (HTTPError, URLError, OSError, ValueError, KeyError, RuntimeError) as error:
        print("configuration viability: FAIL: %s" % error, file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
