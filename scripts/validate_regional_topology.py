#!/usr/bin/env python3
"""Validate SatEdgeSim regional topology files and basic geometry sanity."""

import argparse
import csv
import json
import math
from pathlib import Path


EARTH_RADIUS_KM = 6378.137
GEO_RADIUS_KM = 42164.137
LEO_RADIUS_KM = 6928.137
GROUND_MIN_ELEVATION_DEG = 10.0
ISL_CLEARANCE_KM = 100.0


def vector_norm(vector):
    return math.sqrt(sum(value * value for value in vector))


def dot(a, b):
    return sum(x * y for x, y in zip(a, b))


def blocks(path):
    result = []
    current = None
    with path.open("r", encoding="utf-8", newline="") as stream:
        for row in csv.reader(stream):
            if not row or not any(cell.strip() for cell in row):
                if current:
                    result.append(current)
                    current = None
                continue
            if row[0].strip().strip('"').startswith("Time"):
                if current:
                    result.append(current)
                current = []
                continue
            if len(row) != 4:
                raise AssertionError("{} has malformed row: {}".format(path, row))
            if current is None:
                raise AssertionError("{} has data before a header".format(path))
            current.append((float(row[0]), tuple(float(value) for value in row[1:4])))
    if current:
        result.append(current)
    return result


def assert_close(actual, expected, tolerance, message):
    if abs(actual - expected) > tolerance:
        raise AssertionError("{}: {} != {} (tol {})".format(message, actual, expected, tolerance))


def minimum_segment_radius(first, second):
    delta = tuple(b - a for a, b in zip(first, second))
    denominator = dot(delta, delta)
    if denominator == 0.0:
        return vector_norm(first)
    t = max(0.0, min(1.0, -dot(first, delta) / denominator))
    closest = tuple(first[index] + t * delta[index] for index in range(3))
    return vector_norm(closest)


def ground_sat_visible(ground, satellite, min_elevation_deg=GROUND_MIN_ELEVATION_DEG):
    los = tuple(satellite[index] - ground[index] for index in range(3))
    los_norm = vector_norm(los)
    ground_norm = vector_norm(ground)
    if los_norm == 0.0 or ground_norm == 0.0:
        return True
    sin_elevation = dot(los, ground) / (los_norm * ground_norm)
    return sin_elevation >= math.sin(math.radians(min_elevation_deg))


def validate_scenario(scenario_dir):
    scenario_dir = Path(scenario_dir)
    metadata_path = scenario_dir / "scenario.json"
    if not metadata_path.exists():
        raise AssertionError("missing {}".format(metadata_path))
    metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
    expected = {
        "geo.csv": int(metadata["geoCount"]),
        "leo.csv": int(metadata["leoCount"]),
        "ground.csv": int(metadata["groundCount"]),
    }
    duration = float(metadata["durationSec"])
    step = float(metadata["stepSec"])
    all_data = {}
    for filename, expected_count in expected.items():
        path = scenario_dir / "locations" / filename
        parsed = blocks(path)
        if len(parsed) != expected_count:
            raise AssertionError("{} block count {} != {}".format(filename, len(parsed), expected_count))
        if not parsed:
            raise AssertionError("{} has no blocks".format(filename))
        row_count = len(parsed[0])
        if row_count == 0:
            raise AssertionError("{} has an empty block".format(filename))
        for block_index, block in enumerate(parsed):
            if len(block) != row_count:
                raise AssertionError("{} block {} row count differs".format(filename, block_index + 1))
            assert_close(block[0][0], 0.0, 1e-9, "{} block {} start".format(filename, block_index + 1))
            assert_close(block[-1][0], duration, 1e-9, "{} block {} end".format(filename, block_index + 1))
            for left, right in zip(block, block[1:]):
                assert_close(right[0] - left[0], step, 1e-9, "{} time step".format(filename))
        all_data[filename] = parsed

    for block in all_data["ground.csv"]:
        for _, position in block:
            assert_close(vector_norm(position), EARTH_RADIUS_KM, 1e-5, "ground radius")
        if any(position != block[0][1] for _, position in block[1:]):
            raise AssertionError("ground ECEF position is not constant")
    for block in all_data["geo.csv"]:
        for _, position in block:
            assert_close(vector_norm(position), GEO_RADIUS_KM, 1e-5, "GEO radius")
        if any(position != block[0][1] for _, position in block[1:]):
            raise AssertionError("GEO ECEF position is not constant")
    for block in all_data["leo.csv"]:
        for _, position in block:
            assert_close(vector_norm(position), LEO_RADIUS_KM, 1e-5, "LEO radius")
        if block[-1][1] == block[0][1]:
            raise AssertionError("LEO position did not change")

    # Geometry sanity uses representative vectors independent of generated IDs.
    ground = (EARTH_RADIUS_KM, 0.0, 0.0)
    overhead = (LEO_RADIUS_KM, 0.0, 0.0)
    below_horizon = (-LEO_RADIUS_KM, 0.0, 0.0)
    nearby = (LEO_RADIUS_KM * math.cos(math.radians(10.0)), LEO_RADIUS_KM * math.sin(math.radians(10.0)), 0.0)
    if not ground_sat_visible(ground, overhead):
        raise AssertionError("overhead ground-satellite link should be visible")
    if ground_sat_visible(ground, below_horizon):
        raise AssertionError("below-horizon ground-satellite link should be hidden")
    if minimum_segment_radius(overhead, below_horizon) > EARTH_RADIUS_KM + ISL_CLEARANCE_KM:
        raise AssertionError("opposite-side satellites should be occulted")
    if minimum_segment_radius(overhead, nearby) <= EARTH_RADIUS_KM + ISL_CLEARANCE_KM:
        raise AssertionError("nearby same-side satellites should be visible")

    return metadata


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("scenario_dir")
    args = parser.parse_args()
    metadata = validate_scenario(Path(args.scenario_dir))
    print("validation: PASS")
    print("scenario: {}".format(metadata["name"]))
    print("counts: GEO={} LEO={} ground={}".format(metadata["geoCount"], metadata["leoCount"], metadata["groundCount"]))
    print("time: 0..{} sec, step={} sec".format(metadata["durationSec"], metadata["stepSec"]))


if __name__ == "__main__":
    main()
