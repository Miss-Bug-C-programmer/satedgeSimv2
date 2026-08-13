#!/usr/bin/env python3
"""Generate a deterministic regional GEO/LEO/ground topology for SatEdgeSim.

The CSV writer intentionally follows SatEdgeSim's historical block format:
one header plus one row per epoch for every node, with blank lines between
nodes.  Coordinates are ECEF and are written in kilometres because the Java
loader converts them to metres.
"""

import argparse
import copy
import json
import math
import xml.etree.ElementTree as ET
from pathlib import Path


EARTH_RADIUS_KM = 6378.137
GEO_ALTITUDE_KM = 35786.0
LEO_ALTITUDE_KM = 550.0
LEO_INCLINATION_DEG = 53.0
MU_KM3_S2 = 398600.4418
EARTH_ROTATION_RATE_RAD_S = 7.2921159e-5
DEFAULT_GEO_LONGITUDES_DEG = [80.0, 95.0, 110.0, 125.0]
GROUND_SITES = [
    ("Beijing", 39.9, 116.4),
    ("Shanghai", 31.2, 121.5),
    ("Shenzhen", 22.5, 114.1),
    ("Chengdu", 30.6, 104.1),
    ("Xi'an", 34.3, 108.9),
    ("Wuhan", 30.6, 114.3),
    ("Zhengzhou", 34.7, 113.6),
    ("Kunming", 25.0, 102.7),
    ("Urumqi", 43.8, 87.6),
    ("Harbin", 45.8, 126.5),
    ("Lhasa", 29.7, 91.1),
    ("Haikou", 20.0, 110.3),
]


def ecef_from_lat_lon(lat_deg, lon_deg, radius_km=EARTH_RADIUS_KM):
    lat = math.radians(lat_deg)
    lon = math.radians(lon_deg)
    cos_lat = math.cos(lat)
    return (
        radius_km * cos_lat * math.cos(lon),
        radius_km * cos_lat * math.sin(lon),
        radius_km * math.sin(lat),
    )


def geo_longitude(index):
    """Use the requested default slots, then continue deterministically eastward."""
    if index < len(DEFAULT_GEO_LONGITUDES_DEG):
        return DEFAULT_GEO_LONGITUDES_DEG[index]
    return DEFAULT_GEO_LONGITUDES_DEG[-1] + 15.0 * (index - len(DEFAULT_GEO_LONGITUDES_DEG) + 1)


def rotate_z(vector, angle_rad):
    x, y, z = vector
    c = math.cos(angle_rad)
    s = math.sin(angle_rad)
    return (c * x - s * y, s * x + c * y, z)


def leo_ecef(plane, satellite_in_plane, planes, satellites_per_plane, time_sec):
    """Return one Walker-like circular-orbit satellite position in ECEF km."""
    raan = 2.0 * math.pi * plane / planes
    total_satellites = planes * satellites_per_plane
    phase = 2.0 * math.pi * plane / total_satellites
    argument_of_latitude = (
        2.0 * math.pi * satellite_in_plane / satellites_per_plane
        + phase
        + math.sqrt(MU_KM3_S2 / (EARTH_RADIUS_KM + LEO_ALTITUDE_KM) ** 3) * time_sec
    )
    inclination = math.radians(LEO_INCLINATION_DEG)
    radius = EARTH_RADIUS_KM + LEO_ALTITUDE_KM
    # Perifocal/circular-orbit coordinates, then inclination and RAAN into ECI.
    x_orbit = radius * math.cos(argument_of_latitude)
    y_orbit = radius * math.sin(argument_of_latitude)
    x_eci = math.cos(raan) * x_orbit - math.sin(raan) * math.cos(inclination) * y_orbit
    y_eci = math.sin(raan) * x_orbit + math.cos(raan) * math.cos(inclination) * y_orbit
    z_eci = math.sin(inclination) * y_orbit
    return rotate_z((x_eci, y_eci, z_eci), -EARTH_ROTATION_RATE_RAD_S * time_sec)


def format_coord(value):
    return "{:.9f}".format(value)


def write_blocks(path, nodes, duration_sec, step_sec):
    """Write nodes as SatEdgeSim-compatible CSV blocks."""
    with path.open("w", encoding="utf-8", newline="\n") as stream:
        for node_index, node in enumerate(nodes):
            stream.write('"Time (EpSec)","x (km)","y (km)","z (km)"\n')
            for time_sec in range(0, duration_sec + 1, step_sec):
                x, y, z = node(time_sec)
                stream.write(
                    "{},{},{},{}\n".format(
                        time_sec, format_coord(x), format_coord(y), format_coord(z)
                    )
                )
            if node_index != len(nodes) - 1:
                stream.write("\n")


def clone_xml_template(source_path, output_path, root_tag, child_tag, count, id_tag):
    tree = ET.parse(str(source_path))
    root = tree.getroot()
    templates = root.findall(child_tag)
    if not templates:
        raise ValueError("{} contains no <{}> template".format(source_path, child_tag))
    template = templates[0]
    for child in list(root):
        root.remove(child)
    for identifier in range(1, count + 1):
        node = copy.deepcopy(template)
        location = node.find(".//" + id_tag)
        if location is None:
            raise ValueError("template in {} has no <{}>".format(source_path, id_tag))
        location.text = str(identifier)
        root.append(node)
    if root.tag != root_tag:
        raise ValueError("unexpected XML root {} in {}".format(root.tag, source_path))
    indent = getattr(ET, "indent", None)
    if indent is not None:
        indent(root, space="\t")
    tree.write(str(output_path), encoding="utf-8", xml_declaration=True)


def write_edge_devices_template(source_path, output_path):
    tree = ET.parse(str(source_path))
    root = tree.getroot()
    templates = root.findall("device")
    if not templates:
        raise ValueError("{} contains no <device> template".format(source_path))
    template = copy.deepcopy(templates[0])
    for child in list(root):
        root.remove(child)
    for tag, value in (
        ("mobility", "true"),
        ("battery", "false"),
        ("percentage", "100"),
        ("generateTasks", "true"),
    ):
        element = template.find(".//" + tag)
        if element is None:
            raise ValueError("template in {} has no <{}>".format(source_path, tag))
        element.text = value
    root.append(template)
    indent = getattr(ET, "indent", None)
    if indent is not None:
        indent(root, space="\t")
    tree.write(str(output_path), encoding="utf-8", xml_declaration=True)


def update_properties(source_path, output_path, updates):
    lines = source_path.read_text(encoding="utf-8").splitlines()
    seen = set()
    result = []
    for line in lines:
        stripped = line.strip()
        if not stripped or stripped.startswith("#") or "=" not in line:
            result.append(line)
            continue
        key = line.split("=", 1)[0].strip()
        if key in updates:
            result.append("{}={}".format(key, updates[key]))
            seen.add(key)
        else:
            result.append(line)
    for key, value in updates.items():
        if key not in seen:
            result.append("{}={}".format(key, value))
    output_path.write_text("\n".join(result) + "\n", encoding="utf-8")


def build_metadata(name, geo_count, leo_count, leo_planes, ground_count, duration_sec, step_sec):
    satellites_per_plane = leo_count // leo_planes
    return {
        "name": name,
        "coordinateFrame": "ECEF",
        "coordinateUnit": "km",
        "earthRadiusKm": EARTH_RADIUS_KM,
        "geoAltitudeKm": GEO_ALTITUDE_KM,
        "leoAltitudeKm": LEO_ALTITUDE_KM,
        "leoInclinationDeg": LEO_INCLINATION_DEG,
        "geoCount": geo_count,
        "leoCount": leo_count,
        "leoPlanes": leo_planes,
        "leoSatellitesPerPlane": satellites_per_plane,
        "groundCount": ground_count,
        "durationSec": duration_sec,
        "stepSec": step_sec,
        "geoLongitudesDeg": [geo_longitude(index) for index in range(geo_count)],
        "geoSatellites": [
            {"id": "GEO-{:02d}".format(index + 1), "longitudeDeg": geo_longitude(index)}
            for index in range(geo_count)
        ],
        "groundSites": [
            {"name": name, "latDeg": lat, "lonDeg": lon}
            for name, lat, lon in GROUND_SITES[:ground_count]
        ],
        "leoSatellites": [
            {
                "id": "LEO-{:02d}".format(index + 1),
                "plane": index // satellites_per_plane,
                "indexWithinPlane": index % satellites_per_plane,
            }
            for index in range(leo_count)
        ],
    }


def generate(args):
    if args.leo_planes <= 0:
        raise ValueError("--leo-planes must be positive")
    if args.leo_count <= 0 or args.geo_count <= 0 or args.ground_count <= 0:
        raise ValueError("geo, leo and ground counts must be positive")
    if args.leo_count % args.leo_planes != 0:
        raise ValueError(
            "--leo-count must be divisible by --leo-planes: {} % {} != 0".format(
                args.leo_count, args.leo_planes
            )
        )
    if args.step_sec <= 0 or args.duration_sec < 0:
        raise ValueError("--step-sec must be positive and --duration-sec non-negative")
    if args.duration_sec % args.step_sec != 0:
        raise ValueError("--duration-sec must be divisible by --step-sec")
    if args.ground_count > len(GROUND_SITES):
        raise ValueError("default ground site table only supports up to 12 sites")

    output_dir = Path(args.output_dir)
    locations_dir = output_dir / "locations"
    locations_dir.mkdir(parents=True, exist_ok=True)
    repo_root = Path(__file__).resolve().parents[1]
    settings_dir = repo_root / "SatEdgeSim" / "settings"

    geo_longitudes = [geo_longitude(index) for index in range(args.geo_count)]
    satellites_per_plane = args.leo_count // args.leo_planes
    geo_nodes = [
        lambda time_sec, lon=lon: ecef_from_lat_lon(0.0, lon, EARTH_RADIUS_KM + GEO_ALTITUDE_KM)
        for lon in geo_longitudes
    ]
    leo_nodes = [
        lambda time_sec, plane=plane, sat=sat: leo_ecef(
            plane, sat, args.leo_planes, satellites_per_plane, time_sec
        )
        for plane in range(args.leo_planes)
        for sat in range(satellites_per_plane)
    ]
    ground_nodes = [
        lambda time_sec, lat=lat, lon=lon: ecef_from_lat_lon(lat, lon)
        for _, lat, lon in GROUND_SITES[: args.ground_count]
    ]

    write_blocks(locations_dir / "geo.csv", geo_nodes, args.duration_sec, args.step_sec)
    write_blocks(locations_dir / "leo.csv", leo_nodes, args.duration_sec, args.step_sec)
    write_blocks(locations_dir / "ground.csv", ground_nodes, args.duration_sec, args.step_sec)

    clone_xml_template(
        settings_dir / "cloud.xml",
        output_dir / "cloud.xml",
        "cloud_data_centers",
        "datacenter",
        args.geo_count,
        "cloudID",
    )
    clone_xml_template(
        settings_dir / "edge_datacenters.xml",
        output_dir / "edge_datacenters.xml",
        "edge_datacenters",
        "datacenter",
        args.ground_count,
        "edcID",
    )
    write_edge_devices_template(settings_dir / "edge_devices.xml", output_dir / "edge_devices.xml")

    updates = {
        "simulation_time": 30,
        "initialization_time": 30,
        "update_interval": 1,
        "parallel_simulation": "false",
        "display_real_time_charts": "false",
        "auto_close_real_time_charts": "true",
        "save_charts": "false",
        "edge_device_counter_time": 1,
        "tasks_generation_rate": 2,
        "network_update_interval": 1,
        "wan_propogation_speed": 300000000,
        "orchestration_architectures": "ALL",
        "edge_devices_range": 6000000,
        "edge_datacenters_coverage": 2500000,
        "cloud_coverage": 45000000,
        "ground_min_elevation_deg": 10,
        "isl_min_clearance_m": 100000,
    }
    update_properties(
        settings_dir / "simulation_parameters.properties",
        output_dir / "simulation_parameters.properties",
        updates,
    )

    metadata = build_metadata(
        output_dir.name,
        args.geo_count,
        args.leo_count,
        args.leo_planes,
        args.ground_count,
        args.duration_sec,
        args.step_sec,
    )
    (output_dir / "scenario.json").write_text(
        json.dumps(metadata, indent=2, sort_keys=False) + "\n", encoding="utf-8"
    )
    return output_dir, metadata


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--geo-count", type=int, default=4)
    parser.add_argument("--leo-count", type=int, default=28)
    parser.add_argument("--leo-planes", type=int, default=4)
    parser.add_argument("--ground-count", type=int, default=12)
    parser.add_argument("--duration-sec", type=int, default=3600)
    parser.add_argument("--step-sec", type=int, default=1)
    parser.add_argument(
        "--output-dir",
        default="SatEdgeSim/settings/scenarios/china_regional_4geo_28leo_12ground",
    )
    parser.add_argument("--validate-only", action="store_true")
    args = parser.parse_args()

    output_dir = Path(args.output_dir)
    if args.validate_only:
        from validate_regional_topology import validate_scenario

        validate_scenario(output_dir)
        return

    try:
        output_dir, metadata = generate(args)
        from validate_regional_topology import validate_scenario

        validate_scenario(output_dir)
    except (ValueError, OSError, ET.ParseError) as error:
        parser.error(str(error))
        return

    print("scenario name: {}".format(metadata["name"]))
    print("coordinate frame: {} ({})".format(metadata["coordinateFrame"], metadata["coordinateUnit"]))
    print("GEO count: {}".format(metadata["geoCount"]))
    print("LEO count: {} ({} planes x {})".format(metadata["leoCount"], metadata["leoPlanes"], metadata["leoSatellitesPerPlane"]))
    print("ground count: {}".format(metadata["groundCount"]))
    print("time horizon: {} sec".format(metadata["durationSec"]))
    print("step: {} sec".format(metadata["stepSec"]))
    print("min/max orbital radius: {:.6f}/{:.6f} km".format(EARTH_RADIUS_KM + LEO_ALTITUDE_KM, EARTH_RADIUS_KM + GEO_ALTITUDE_KM))
    print("output files:")
    for path in sorted(output_dir.rglob("*")):
        if path.is_file():
            print("  {}".format(path))
    print("validation: PASS")


if __name__ == "__main__":
    main()
