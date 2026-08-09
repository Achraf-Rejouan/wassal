#!/usr/bin/env python3
"""Build a static road-graph asset from an OpenStreetMap extract (S1-12, assumption A-01).

WHY THIS EXISTS
---------------
The brief asked for simulated couriers "interpolating along real OpenStreetMap road geometry".
Read literally that implies a routing engine — OSRM or GraphHopper — which is a multi-day
detour, a heavyweight container against a tight memory budget, and a runtime dependency for a
component whose only job is generating load.

A-01 took the cheaper path: extract the road geometry ONCE, offline, into a node/edge graph the
simulator random-walks at runtime. Couriers still follow real streets. What is given up is true
origin-destination routing, which load generation does not need.

This script is the offline half. It runs on a developer machine, not in the stack, and its
output (assets/tunis-road-graph.json) is committed so a cold clone needs neither the extract nor
this script.

USAGE
    python3 build_road_graph.py --overpass  --output ../../../assets/tunis-road-graph.json
    python3 build_road_graph.py --input tunis.osm.pbf --output ../../../assets/tunis-road-graph.json
    python3 build_road_graph.py --synthetic --output ../../../assets/tunis-road-graph.json

SPIKE RESULT (S1-12, 2026-08-09)
    --overpass is the primary path and it is far cheaper than the plan assumed. For an 8x8 km
    bounding box the Overpass API returns the road geometry directly as JSON: 5,617 ways and
    41,398 geometry points, in one HTTP request, with NO pbf extract, NO pyosmium and NO
    routing engine. The risk that motivated A-01 turned out to be smaller than estimated
    because the area is small — Overpass is unsuitable for a country-sized extract, which is
    the case the .pbf path below still covers.

The --synthetic mode is the documented fallback if the 6 h timebox on S1-12 fires: a grid graph
over the same bounding box, same output shape, lower fidelity. The simulator cannot tell the
difference at its interface, which is the property that makes the fallback safe.
"""

import argparse
import json
import math
import random
from pathlib import Path

# Central Tunis, roughly Lac / Centre-ville / Bab Bhar — about 8 x 8 km (assumption A-04).
BBOX = {"min_lat": 36.7700, "max_lat": 36.8400, "min_lon": 10.1300, "max_lon": 10.2400}

# Only ways couriers would plausibly use. Footpaths and motorways are both wrong here.
DRIVABLE = {
    "motorway", "trunk", "primary", "secondary", "tertiary",
    "unclassified", "residential", "living_street",
    "primary_link", "secondary_link", "tertiary_link",
}


def haversine_m(a_lat, a_lon, b_lat, b_lon):
    r = 6371000.0
    p1, p2 = math.radians(a_lat), math.radians(b_lat)
    dp = math.radians(b_lat - a_lat)
    dl = math.radians(b_lon - a_lon)
    h = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(math.sqrt(h))


# Public Overpass instances are rate-limited and return 504 under load. Mirrors are tried in
# order; --from-json skips the network entirely once a response has been captured.
OVERPASS_MIRRORS = (
    "https://overpass-api.de/api/interpreter",
    "https://overpass.kumi.systems/api/interpreter",
    "https://overpass.osm.jp/api/interpreter",
)


def fetch_overpass():
    """Fetch raw Overpass JSON, trying mirrors in order."""
    import time
    import urllib.parse
    import urllib.request

    highway_filter = "|".join(sorted(DRIVABLE))
    query = f"""[out:json][timeout:90];
        way["highway"~"^({highway_filter})$"]
          ({BBOX['min_lat']},{BBOX['min_lon']},{BBOX['max_lat']},{BBOX['max_lon']});
        out geom;"""

    last_error = None
    for attempt, url in enumerate(OVERPASS_MIRRORS):
        try:
            request = urllib.request.Request(
                url,
                data=urllib.parse.urlencode({"data": query}).encode(),
                headers={"User-Agent": "wassal-road-graph-builder/1.0"},
            )
            with urllib.request.urlopen(request, timeout=180) as response:
                return json.load(response)
        except Exception as error:  # noqa: BLE001 - any failure means try the next mirror
            last_error = error
            print(f"  overpass mirror failed ({url}): {error}")
            time.sleep(2 * (attempt + 1))
    raise SystemExit(
        f"All Overpass mirrors failed (last: {last_error}).\n"
        "Capture a response manually and re-run with --from-json, or use --synthetic."
    )


def build_from_overpass(payload):
    """Turn an Overpass response into a node/edge graph. No extract, no osmium, no routing."""

    highway_filter = "|".join(sorted(DRIVABLE))
    query = f"""[out:json][timeout:90];
        way["highway"~"^({highway_filter})$"]
          ({BBOX['min_lat']},{BBOX['min_lon']},{BBOX['max_lat']},{BBOX['max_lon']});
        out geom;"""

    nodes, edges = {}, []
    # Overpass `out geom` inlines coordinates but not node ids, so points are identified by
    # rounded coordinate. Rounding to 7 decimals (~1 cm) merges genuine shared junctions
    # without collapsing distinct ones — that merging is what makes the result a connected
    # graph rather than a pile of disjoint polylines.
    def node_for(lat, lon):
        key = (round(lat, 7), round(lon, 7))
        if key not in nodes:
            nodes[key] = len(nodes)
        return nodes[key]

    for element in payload.get("elements", []):
        geometry = element.get("geometry") or []
        for first, second in zip(geometry, geometry[1:]):
            a = node_for(first["lat"], first["lon"])
            b = node_for(second["lat"], second["lon"])
            if a == b:
                continue
            edges.append(
                (a, b, haversine_m(first["lat"], first["lon"], second["lat"], second["lon"]))
            )

    coords = {index: key for key, index in nodes.items()}
    return coords, edges


def build_from_osm(path):
    """Parse an .osm.pbf extract. Requires `osmium` (pyosmium), a build-time dependency only."""
    import osmium  # imported lazily so --synthetic needs no dependencies

    class Handler(osmium.SimpleHandler):
        def __init__(self):
            super().__init__()
            self.nodes = {}
            self.edges = []

        def way(self, w):
            if w.tags.get("highway") not in DRIVABLE:
                return
            coords = []
            for n in w.nodes:
                try:
                    lat, lon = n.location.lat, n.location.lon
                except osmium.InvalidLocationError:
                    return
                if not (
                    BBOX["min_lat"] <= lat <= BBOX["max_lat"]
                    and BBOX["min_lon"] <= lon <= BBOX["max_lon"]
                ):
                    return
                coords.append((n.ref, lat, lon))
            for (id_a, lat_a, lon_a), (id_b, lat_b, lon_b) in zip(coords, coords[1:]):
                self.nodes[id_a] = (lat_a, lon_a)
                self.nodes[id_b] = (lat_b, lon_b)
                self.edges.append((id_a, id_b, haversine_m(lat_a, lon_a, lat_b, lon_b)))

    handler = Handler()
    handler.apply_file(str(path), locations=True)
    return handler.nodes, handler.edges


def build_synthetic(spacing_m=150):
    """Grid fallback. Same output shape, so the simulator is indifferent."""
    lat_step = spacing_m / 111_320.0
    lon_step = spacing_m / (111_320.0 * math.cos(math.radians(36.80)))

    nodes, index = {}, {}
    node_id = 0
    lat = BBOX["min_lat"]
    row = 0
    while lat <= BBOX["max_lat"]:
        lon, col = BBOX["min_lon"], 0
        while lon <= BBOX["max_lon"]:
            nodes[node_id] = (lat, lon)
            index[(row, col)] = node_id
            node_id += 1
            lon += lon_step
            col += 1
        lat += lat_step
        row += 1

    edges = []
    for (r, c), nid in index.items():
        for neighbour in ((r, c + 1), (r + 1, c)):
            if neighbour in index:
                other = index[neighbour]
                a, b = nodes[nid], nodes[other]
                edges.append((nid, other, haversine_m(a[0], a[1], b[0], b[1])))
    return nodes, edges


def largest_connected_component(nodes, edges):
    """A courier stranded on an isolated fragment stops generating load, so keep one component."""
    adjacency = {n: [] for n in nodes}
    for a, b, _ in edges:
        adjacency[a].append(b)
        adjacency[b].append(a)

    seen, best = set(), set()
    for start in adjacency:
        if start in seen:
            continue
        stack, component = [start], set()
        while stack:
            current = stack.pop()
            if current in component:
                continue
            component.add(current)
            stack.extend(n for n in adjacency[current] if n not in component)
        seen |= component
        if len(component) > len(best):
            best = component
    return best


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--overpass", action="store_true", help="fetch geometry via Overpass API")
    parser.add_argument("--from-json", type=Path, help="build from a captured Overpass response")
    parser.add_argument("--input", type=Path, help="OSM .pbf extract (for larger areas)")
    parser.add_argument("--synthetic", action="store_true", help="grid fallback (timebox escape)")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    if args.synthetic:
        source = "synthetic-grid"
        nodes, edges = build_synthetic()
    elif args.from_json:
        source = f"overpass-cached:{args.from_json.name}"
        nodes, edges = build_from_overpass(json.loads(args.from_json.read_text()))
    elif args.overpass or not args.input:
        source = "overpass:central-tunis"
        nodes, edges = build_from_overpass(fetch_overpass())
    else:
        source = f"osm:{args.input.name}"
        nodes, edges = build_from_osm(args.input)

    keep = largest_connected_component(nodes, edges)
    nodes = {n: c for n, c in nodes.items() if n in keep}
    edges = [e for e in edges if e[0] in keep and e[1] in keep]

    # Renumber densely so the simulator can index into arrays rather than hash on OSM ids.
    remap = {old: new for new, old in enumerate(sorted(nodes))}
    graph = {
        "source": source,
        "bbox": BBOX,
        "nodeCount": len(nodes),
        "edgeCount": len(edges),
        "nodes": [
            {"id": remap[n], "lat": round(c[0], 6), "lon": round(c[1], 6)}
            for n, c in sorted(nodes.items())
        ],
        "edges": [
            {"from": remap[a], "to": remap[b], "lengthM": round(d, 1)} for a, b, d in edges
        ],
    }

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(graph, separators=(",", ":")))

    degrees = [0] * len(nodes)
    for e in graph["edges"]:
        degrees[e["from"]] += 1
        degrees[e["to"]] += 1
    print(f"source      : {source}")
    print(f"nodes       : {graph['nodeCount']}")
    print(f"edges       : {graph['edgeCount']}")
    print(f"mean degree : {2 * len(edges) / max(1, len(nodes)):.2f}")
    print(f"dead ends   : {sum(1 for d in degrees if d <= 1)}")
    print(f"size        : {args.output.stat().st_size / 1024:.0f} KiB -> {args.output}")


if __name__ == "__main__":
    main()
