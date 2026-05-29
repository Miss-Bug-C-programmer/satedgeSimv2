#!/usr/bin/env python3
"""Minimal Python client for the SatEdgeSim REST RL server."""

import argparse
import json
import time
from typing import Any, Dict

import requests


def post(base_url: str, path: str, payload: Dict[str, Any]) -> Dict[str, Any]:
    r = requests.post(f"{base_url}{path}", json=payload, timeout=120)
    r.raise_for_status()
    return r.json()


def get(base_url: str, path: str) -> Dict[str, Any]:
    r = requests.get(f"{base_url}{path}", timeout=120)
    r.raise_for_status()
    return r.json()


def choose_first_feasible(state: Dict[str, Any]) -> int:
    for vm in state.get("candidateVms", []):
        if vm.get("feasible"):
            return int(vm["vmIndex"])
    return -1


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://127.0.0.1:8088")
    parser.add_argument("--devices", type=int, default=-1)
    args = parser.parse_args()

    state = post(args.base_url, "/reset", {
        "devicesCount": args.devices,
        "algorithmIndex": 0,
        "architectureIndex": 0,
        "waitForFirstDecision": True,
        "waitTimeoutMs": 30000,
    })
    print("RESET:", json.dumps({"status": state.get("status"), "requestId": state.get("requestId")}, indent=2))

    steps = 0
    while state.get("status") == "WAITING_FOR_ACTION":
        target = choose_first_feasible(state)
        action = {
            "requestId": state["requestId"],
            "targetVmIndex": target,
            "cpuShare": 1.0,
            "bandwidthShare": 1.0,
            "txPowerRatio": 1.0,
            "queuePriority": 1.0,
        }
        state = post(args.base_url, "/step", {"action": action, "waitTimeoutMs": 30000})
        steps += 1
        if steps % 20 == 0:
            metrics = get(args.base_url, "/get_metrics")
            print(f"steps={steps} status={state.get('status')} metrics={json.dumps(metrics, ensure_ascii=False)[:300]}")
        time.sleep(0.001)

    print("FINAL STATE:", json.dumps(state, ensure_ascii=False, indent=2)[:4000])
    print("FINAL METRICS:", json.dumps(get(args.base_url, "/get_metrics"), ensure_ascii=False, indent=2))
    post(args.base_url, "/close", {})


if __name__ == "__main__":
    main()
