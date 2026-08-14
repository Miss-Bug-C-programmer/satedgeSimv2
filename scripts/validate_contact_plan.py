#!/usr/bin/env python3
"""REST consistency smoke test for the deterministic contact backend."""

import argparse
import json
import sys
import time
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen


def request_json(base_url, method, path, payload=None, timeout=120):
    body = None if payload is None else json.dumps(payload).encode("utf-8")
    request = Request(base_url.rstrip("/") + path, data=body, method=method)
    request.add_header("Content-Type", "application/json")
    with urlopen(request, timeout=timeout) as response:
        return json.loads(response.read().decode("utf-8"))


def wait_for_health(base_url, timeout_sec):
    deadline = time.time() + timeout_sec
    while time.time() < deadline:
        try:
            result = request_json(base_url, "GET", "/health", timeout=3)
            if result.get("status") == "ok" or result.get("ok") is True:
                return
        except (HTTPError, URLError, OSError, ValueError):
            pass
        time.sleep(0.25)
    raise RuntimeError("REST server did not become healthy")


def node_key(node):
    return (node["type"], int(node["deviceId"]))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://127.0.0.1:8088")
    parser.add_argument("--devices", type=int, default=28)
    parser.add_argument("--horizon-sec", type=float, default=600.0)
    parser.add_argument("--wait-timeout-sec", type=float, default=30.0)
    args = parser.parse_args()

    try:
        wait_for_health(args.base_url, args.wait_timeout_sec)
        reset = request_json(args.base_url, "POST", "/reset", {
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
        state = None
        deadline = time.time() + 60.0
        while time.time() < deadline:
            state = request_json(args.base_url, "GET", "/get_state")
            if len(state.get("datacenters", [])) >= args.devices:
                break
            time.sleep(0.25)
        if state is None or len(state.get("datacenters", [])) < args.devices:
            raise RuntimeError("reset did not create the requested active LEO topology")
        topology = request_json(args.base_url, "GET", "/topology/current")
        nodes = topology.get("nodes", [])
        by_type = {}
        for node in nodes:
            by_type.setdefault(node["type"], []).append(node)
        source = by_type["EDGE_DEVICE"][0]
        candidate_pairs = [
            (source, by_type["EDGE_DEVICE"][1]),
            (source, by_type["CLOUD"][0]),
            (source, by_type["EDGE_DATACENTER"][0]),
        ]
        link_map = {}
        for link in topology.get("links", []):
            link_map[(
                (link["sourceType"], int(link["sourceDeviceId"])),
                (link["destinationType"], int(link["destinationDeviceId"])),
            )] = link

        tested = 0
        available_matches = 0
        lifetime_matches = 0
        next_window_matches = 0
        censored_cases = 0
        failures = []
        for source_node, destination_node in candidate_pairs:
            forecast = request_json(args.base_url, "POST", "/topology/contact_plan", {
                "source": source_node,
                "destination": destination_node,
                "horizonSec": args.horizon_sec,
            })
            tested += 1
            key = (node_key(source_node), node_key(destination_node))
            current_link = link_map.get(key)
            if current_link is not None and bool(current_link["available"]) == bool(forecast["availableNow"]):
                available_matches += 1
            else:
                failures.append("availableNow mismatch for %s -> %s" % (source_node, destination_node))

            windows = forecast.get("windows", [])
            if forecast["availableNow"] and forecast.get("currentContactEndSec") is not None:
                current_end = float(forecast["currentContactEndSec"])
                if abs(float(forecast["remainingLifetimeSec"]) - (current_end - float(forecast["simulationTimeSec"]))) < 0.2:
                    lifetime_matches += 1
            elif forecast.get("nextContactStartSec") is not None:
                if float(forecast["nextContactStartSec"]) > float(forecast["forecastStartSec"]):
                    next_window_matches += 1
            if forecast.get("remainingLifetimeCensored"):
                censored_cases += 1
            if windows and any(window.get("rightCensored") for window in windows):
                censored_cases += 1

        source_node = by_type["EDGE_DEVICE"][0]
        for destination_type in ("EDGE_DEVICE", "CLOUD", "EDGE_DATACENTER"):
            for destination_node in by_type[destination_type]:
                if node_key(destination_node) == node_key(source_node):
                    continue
                forecast = request_json(args.base_url, "POST", "/topology/contact_plan", {
                    "source": source_node,
                    "destination": destination_node,
                    "horizonSec": args.horizon_sec,
                })
                if forecast.get("nextContactStartSec") is not None and not forecast.get("availableNow"):
                    if float(forecast["nextContactStartSec"]) > float(forecast["simulationTimeSec"]):
                        next_window_matches += 1
                        break

        stats = request_json(args.base_url, "GET", "/debug/contact_plan_stats")
        if not state.get("datacenters"):
            failures.append("get_state did not return datacenters")
        if stats.get("pairsCached", 0) < tested:
            failures.append("contact plan cache did not contain tested pairs")
        print("pairs tested=%d" % tested)
        print("available-now matches=%d" % available_matches)
        print("lifetime matches=%d" % lifetime_matches)
        print("next-window matches=%d" % next_window_matches)
        print("censored cases=%d" % censored_cases)
        print("contact plan stats=%s" % json.dumps(stats, sort_keys=True))
        print("failures=%d" % len(failures))
        if failures:
            for failure in failures:
                print("FAIL: " + failure, file=sys.stderr)
            return 1
        print("validate_contact_plan: PASS")
        return 0
    except (HTTPError, URLError, OSError, ValueError, KeyError, RuntimeError) as error:
        print("validate_contact_plan: FAIL: %s" % error, file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
