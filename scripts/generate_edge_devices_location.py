#!/usr/bin/env python3
from __future__ import annotations

import argparse
import math
from pathlib import Path


EARTH_RADIUS_KM = 6371.0
DEFAULT_ALTITUDE_KM = 550.0


def format_block(device_id: int, seconds: int, radius_km: float, phase: float, z_phase: float) -> str:
    lines = [
        f"\"Time (EpSec)\",\"mist{device_id} - x (km)\",\"mist{device_id} - y (km)\",\"mist{device_id} - z (km)\""
    ]
    for sec in range(seconds + 1):
        angle = phase + (2.0 * math.pi * sec / max(seconds, 1))
        z_angle = z_phase + (4.0 * math.pi * sec / max(seconds, 1))
        x = radius_km * math.cos(angle)
        y = radius_km * math.sin(angle)
        z = 15.0 * math.sin(z_angle)
        lines.append(f"{sec:.3f},{x:.6f},{y:.6f},{z:.6f}")
    return "\n".join(lines)


def main() -> None:
    parser = argparse.ArgumentParser(description="Generate a lightweight SatEdgeSim edge device mobility CSV.")
    parser.add_argument("--devices", type=int, default=20, help="Number of edge devices to generate.")
    parser.add_argument("--seconds", type=int, default=7200, help="Simulation duration in seconds.")
    parser.add_argument(
        "--output",
        type=Path,
        default=Path("SatEdgeSim/settings/locationflie/edge_devices/mist Fixed Position.csv"),
        help="Output CSV path.",
    )
    args = parser.parse_args()

    if args.devices <= 0:
        raise SystemExit("--devices must be positive")
    if args.seconds <= 0:
        raise SystemExit("--seconds must be positive")

    radius_km = EARTH_RADIUS_KM + DEFAULT_ALTITUDE_KM
    blocks = []
    for index in range(args.devices):
        device_id = index + 1
        phase = 2.0 * math.pi * index / args.devices
        z_phase = math.pi * index / args.devices
        blocks.append(format_block(device_id, args.seconds, radius_km, phase, z_phase))

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text("\n\n".join(blocks) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
