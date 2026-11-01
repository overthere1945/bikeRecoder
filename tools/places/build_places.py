#!/usr/bin/env python3
"""Builds app/src/main/assets/places/places.bin (share cards spec §4.2-§4.3).

Standard library only. Inputs (next to this script):
  places.tsv                   routes: id, kind, tag, group, name_regex[, bbox]
  sources/100-mountains.csv    Korea Forest Service 100 mountains: name, sido, height_m, tag
  sources/peak-overrides.tsv   summit coordinates for mountains the name rule cannot resolve
  sources/overpass/*.ql        Overpass queries
  sources/osm/*.json           Overpass answers (git-ignored; fetch with --download)

Usage:
  python3 -I tools/places/build_places.py --download   # fetch sources/osm/*.json, then build
  python3 -I tools/places/build_places.py              # build from the downloaded files
"""
import argparse
import csv
import json
import math
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent
SOURCES = HERE / "sources"
OVERPASS = SOURCES / "overpass"
OSM = SOURCES / "osm"
DEFAULT_OUT = REPO / "app" / "src" / "main" / "assets" / "places"

QUERIES = ("routes", "centers", "peaks")
ENDPOINTS = (
    "https://overpass-api.de/api/interpreter",
    "https://overpass.kumi.systems/api/interpreter",
)
USER_AGENT = "bikeRecoder-dev/1.0 (personal app)"  # Overpass answers 406 without one

FORMAT_VERSION = 1
KIND_ROUTE, KIND_CENTER, KIND_PEAK = 0, 1, 2
KIND_NAMES = {KIND_ROUTE: "ROUTE", KIND_CENTER: "CENTER", KIND_PEAK: "PEAK"}
NATIONAL_GROUP = "#국토종주"
PEAK_GROUP = "#100대명산"
SIMPLIFY_M = 20.0
CENTER_MAX_M = 2000.0
CENTER_DEDUP_M = 300.0
PEAK_HEIGHT_TOLERANCE_M = 30.0
MAX_BYTES = 1_000_000
M_PER_DEG_LAT = 110_540.0
M_PER_DEG_LON_EQ = 111_320.0

# Generous bounding boxes (lat0, lat1, lon0, lon1) of the provinces and metropolitan cities.
SIDO_BBOX = {
    "서울": (37.41, 37.72, 126.73, 127.27),
    "부산": (34.87, 35.40, 128.75, 129.32),
    "대구": (35.60, 36.02, 128.34, 128.78),
    "인천": (37.00, 37.98, 124.60, 126.80),
    "광주": (35.05, 35.26, 126.64, 127.03),
    "대전": (36.18, 36.50, 127.24, 127.56),
    "울산": (35.32, 35.73, 128.95, 129.47),
    "세종": (36.40, 36.73, 127.13, 127.40),
    "경기": (36.89, 38.30, 126.37, 127.86),
    "강원": (37.02, 38.62, 127.08, 129.37),
    "충북": (36.00, 37.27, 127.27, 128.66),
    "충남": (35.97, 37.07, 125.50, 127.65),
    "전북": (35.28, 36.16, 125.90, 127.92),
    "전남": (33.90, 35.51, 125.00, 127.92),
    "경북": (35.57, 37.60, 127.80, 131.90),
    "경남": (34.46, 35.92, 127.57, 129.23),
    "제주": (33.10, 33.60, 126.10, 127.00),
}
BBOX_MARGIN_DEG = 0.05


# ---------------------------------------------------------------- download

def download(names):
    OSM.mkdir(parents=True, exist_ok=True)
    for name in names:
        query = (OVERPASS / f"{name}.ql").read_text(encoding="utf-8")
        body = urllib.parse.urlencode({"data": query}).encode("utf-8")
        for attempt in range(6):
            url = ENDPOINTS[attempt % len(ENDPOINTS)]
            req = urllib.request.Request(url, data=body, headers={"User-Agent": USER_AGENT})
            try:
                with urllib.request.urlopen(req, timeout=600) as resp:
                    data = resp.read()
                break
            except urllib.error.HTTPError as e:
                if e.code not in (429, 502, 503, 504):
                    raise
                print(f"{name}: HTTP {e.code} from {url}, retrying", file=sys.stderr)
                time.sleep(30 * (attempt + 1))
        else:
            sys.exit(f"{name}: download failed")
        (OSM / f"{name}.json").write_bytes(data)
        print(f"downloaded {name}: {len(data)} bytes")


# ---------------------------------------------------------------- geometry

def plane_distance(a, b):
    k = math.cos(math.radians((a[0] + b[0]) / 2)) * M_PER_DEG_LON_EQ
    return math.hypot((b[1] - a[1]) * k, (b[0] - a[0]) * M_PER_DEG_LAT)


def line_km(points):
    return sum(plane_distance(a, b) for a, b in zip(points, points[1:])) / 1000.0


def distance_to_piece(p, a, b):
    """Shortest distance from p to the piece a-b on a plane centred at p (as PlaceMatcher)."""
    k = math.cos(math.radians(p[0])) * M_PER_DEG_LON_EQ
    ax, ay = (a[1] - p[1]) * k, (a[0] - p[0]) * M_PER_DEG_LAT
    bx, by = (b[1] - p[1]) * k, (b[0] - p[0]) * M_PER_DEG_LAT
    dx, dy = bx - ax, by - ay
    len2 = dx * dx + dy * dy
    t = 0.0 if len2 == 0.0 else max(0.0, min(1.0, (-ax * dx - ay * dy) / len2))
    return math.hypot(ax + dx * t, ay + dy * t)


def simplify(points, tolerance_m):
    """Douglas-Peucker on a local equirectangular plane; keeps both ends."""
    if len(points) < 3:
        return list(points)
    lat0 = sum(p[0] for p in points) / len(points)
    k = math.cos(math.radians(lat0)) * M_PER_DEG_LON_EQ
    xy = [(p[1] * k, p[0] * M_PER_DEG_LAT) for p in points]
    keep = [False] * len(points)
    keep[0] = keep[-1] = True
    stack = [(0, len(points) - 1)]
    while stack:
        i, j = stack.pop()
        (ax, ay), (bx, by) = xy[i], xy[j]
        dx, dy = bx - ax, by - ay
        len2 = dx * dx + dy * dy
        worst, worst_d = -1, tolerance_m
        for m in range(i + 1, j):
            px, py = xy[m]
            t = 0.0 if len2 == 0.0 else max(0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / len2))
            d = math.hypot(px - (ax + dx * t), py - (ay + dy * t))
            if d > worst_d:
                worst, worst_d = m, d
        if worst >= 0:
            keep[worst] = True
            stack.append((i, worst))
            stack.append((worst, j))
    return [p for p, kept in zip(points, keep) if kept]


def quantize(points):
    """Rounds to 1e-5 degrees and drops consecutive duplicates."""
    out = []
    for lat, lon in points:
        q = (round(lat * 1e5), round(lon * 1e5))
        if not out or out[-1] != q:
            out.append(q)
    return out


def join_ways(ways):
    """Joins way polylines whose endpoints touch. Deterministic for a given input order."""
    def key(p):
        return (round(p[0], 7), round(p[1], 7))

    ends = {}
    for i, w in enumerate(ways):
        ends.setdefault(key(w[0]), []).append(i)
        ends.setdefault(key(w[-1]), []).append(i)
    used = [False] * len(ways)

    def take(at):
        for j in ends.get(at, ()):
            if not used[j]:
                used[j] = True
                return j
        return None

    lines = []
    for i, w in enumerate(ways):
        if used[i]:
            continue
        used[i] = True
        line = list(w)
        while True:  # forward
            j = take(key(line[-1]))
            if j is None:
                break
            seg = ways[j] if key(ways[j][0]) == key(line[-1]) else ways[j][::-1]
            line.extend(seg[1:])
        while True:  # backward
            j = take(key(line[0]))
            if j is None:
                break
            seg = ways[j] if key(ways[j][-1]) == key(line[0]) else ways[j][::-1]
            line[0:0] = seg[:-1]
        lines.append(line)
    return lines


# ---------------------------------------------------------------- binary writer

def varint(out, value):
    if value < 0:
        raise ValueError(f"negative varint {value}")
    while value > 0x7F:
        out.append((value & 0x7F) | 0x80)
        value >>= 7
    out.append(value)


def zigzag(v):
    if not -(1 << 31) <= v < (1 << 31):
        raise ValueError(f"coordinate out of range {v}")
    return ((v << 1) ^ (v >> 31)) & 0xFFFFFFFF


def string(out, s):
    data = s.encode("utf-8")
    varint(out, len(data))
    out.extend(data)


def encode(built_on, places):
    out = bytearray(b"PLC1")
    out += FORMAT_VERSION.to_bytes(2, "little")
    string(out, built_on)
    varint(out, len(places))
    for p in places:
        out.append(p["kind"])
        string(out, p["tag"])
        string(out, p["group"] or "")
        if p["kind"] == KIND_CENTER:
            varint(out, p["route_index"])
        varint(out, len(p["lines"]))
        for line in p["lines"]:
            varint(out, len(line))
            prev = (0, 0)
            for i, (lat, lon) in enumerate(line):
                if i == 0:
                    varint(out, zigzag(lat))
                    varint(out, zigzag(lon))
                else:
                    varint(out, zigzag(lat - prev[0]))
                    varint(out, zigzag(lon - prev[1]))
                prev = (lat, lon)
    return bytes(out)


# ---------------------------------------------------------------- inputs

def load_osm(name):
    path = OSM / f"{name}.json"
    if not path.exists():
        sys.exit(f"missing {path}; run with --download first")
    data = json.loads(path.read_text(encoding="utf-8"))
    stamp = data.get("osm3s", {}).get("timestamp_osm_base", "")
    return data["elements"], stamp[:10]


def read_tsv(path):
    rows = []
    with open(path, encoding="utf-8", newline="") as f:
        for line in f:
            line = line.rstrip("\n")
            if not line.strip() or line.startswith("# "):  # tags start with "#" but no space
                continue
            rows.append(line.split("\t"))
    return rows[1:]  # header


def parse_ele(tags):
    raw = tags.get("ele", "").replace(",", "").replace("m", "").strip()
    try:
        return float(raw)
    except ValueError:
        return None


def in_sido(lat, lon, sido_list):
    for sido in sido_list:
        lat0, lat1, lon0, lon1 = SIDO_BBOX[sido]
        m = BBOX_MARGIN_DEG
        if lat0 - m <= lat <= lat1 + m and lon0 - m <= lon <= lon1 + m:
            return True
    return False


# ---------------------------------------------------------------- builders

def build_routes(elements):
    relations = sorted((e for e in elements if e["type"] == "relation"), key=lambda e: e["id"])
    routes, summary = [], []
    for row in read_tsv(HERE / "places.tsv"):
        pid, kind, tag, group, regex = row[:5]
        bbox = tuple(float(v) for v in row[5].split(",")) if len(row) > 5 and row[5] else None
        if kind != "ROUTE":
            sys.exit(f"places.tsv {pid}: unknown kind {kind}")
        pattern = re.compile(regex)
        matched = []
        for rel in relations:
            if not pattern.search(rel.get("tags", {}).get("name", "")):
                continue
            if bbox:
                b = rel.get("bounds")
                if not b or not (bbox[0] <= b["minlat"] and b["maxlat"] <= bbox[1]
                                 and bbox[2] <= b["minlon"] and b["maxlon"] <= bbox[3]):
                    continue
            matched.append(rel)
        ways, seen = [], set()
        for rel in matched:
            for m in rel.get("members", []):
                if m["type"] != "way" or m["ref"] in seen:
                    continue
                geom = [(g["lat"], g["lon"]) for g in m.get("geometry", []) if g]
                if len(geom) >= 2:
                    seen.add(m["ref"])
                    ways.append(geom)
        if not ways:
            summary.append(("ROUTE", tag, 0, 0.0, f"DROPPED: no OSM relation matches /{regex}/"))
            continue
        lines = [quantize(simplify(line, SIMPLIFY_M)) for line in join_ways(ways)]
        lines = [line for line in lines if len(line) >= 2]
        points = sum(len(line) for line in lines)
        km = sum(line_km([(a / 1e5, b / 1e5) for a, b in line]) for line in lines)
        source = "osm relation " + ",".join(str(r["id"]) for r in matched)
        routes.append({"kind": KIND_ROUTE, "id": pid, "tag": tag, "group": group or None,
                       "route_index": None, "lines": lines})
        summary.append(("ROUTE", tag, points, km, source))
    return routes, summary


def build_centers(elements, routes):
    national = [(i, r) for i, r in enumerate(routes) if r["group"] == NATIONAL_GROUP]
    pieces = []  # (route index, a, b) in degrees
    for i, r in national:
        for line in r["lines"]:
            pts = [(a / 1e5, b / 1e5) for a, b in line]
            pieces.extend((i, a, b) for a, b in zip(pts, pts[1:]))
    nodes = sorted((e for e in elements if e["type"] == "node"), key=lambda e: e["id"])
    # Stamp booths first so a duplicate info point never replaces the booth.
    nodes.sort(key=lambda e: 0 if e.get("tags", {}).get("checkpoint") == "cycling" else 1)
    kept, summary = [], []
    for n in nodes:
        name = n["tags"]["name"]
        p = (n["lat"], n["lon"])
        dup = next((c for c in kept if plane_distance(p, c["point"]) <= CENTER_DEDUP_M), None)
        if dup:
            summary.append(("CENTER", "#" + name.replace(" ", ""), 0, 0.0,
                            f"DROPPED: duplicate of node {dup['node']} within {CENTER_DEDUP_M:.0f} m"))
            continue
        best, best_d = None, CENTER_MAX_M
        lat_pad = CENTER_MAX_M / M_PER_DEG_LAT
        for i, a, b in pieces:
            if min(a[0], b[0]) - lat_pad > p[0] or max(a[0], b[0]) + lat_pad < p[0]:
                continue
            d = distance_to_piece(p, a, b)
            if d <= best_d:
                best, best_d = i, d
        if best is None:
            summary.append(("CENTER", "#" + name.replace(" ", ""), 0, 0.0,
                            f"DROPPED: node {n['id']} {name}: no national route within 2 km"))
            continue
        kept.append({"node": n["id"], "name": name, "point": p, "route": best, "dist": best_d})
    kept.sort(key=lambda c: (c["route"], c["point"][0], c["point"][1], c["node"]))
    centers = []
    for c in kept:
        tag = "#" + c["name"].replace(" ", "")
        centers.append({"kind": KIND_CENTER, "tag": tag, "group": NATIONAL_GROUP,
                        "route_index": c["route"], "lines": [quantize([c["point"]])]})
        summary.append(("CENTER", tag, 1, 0.0,
                        f"osm node {c['node']} {c['name']} -> {routes[c['route']]['tag']} "
                        f"({c['dist']:.0f} m)"))
    return centers, summary


def build_peaks(elements):
    nodes = {e["id"]: e for e in elements if e["type"] == "node"}
    overrides = {}
    for row in read_tsv(SOURCES / "peak-overrides.tsv"):
        tag, lat, lon = row[0], float(row[1]), float(row[2])
        overrides[tag] = ((lat, lon), row[3] if len(row) > 3 else "", row[4] if len(row) > 4 else "")
    peaks, summary, failures, used_overrides = [], [], [], set()
    with open(SOURCES / "100-mountains.csv", encoding="utf-8", newline="") as f:
        rows = list(csv.DictReader(f))
    tags = [r["tag"] for r in rows]
    if len(set(tags)) != len(tags):
        sys.exit("100-mountains.csv: duplicate tags")
    for r in rows:
        name, height, tag = r["name"], float(r["height_m"]), r["tag"]
        sido = r["sido"].split("/")
        for s in sido:
            if s not in SIDO_BBOX:
                sys.exit(f"100-mountains.csv {name}: unknown sido {s}")
        # "북한산", "북한산(백운대)", "덕유산 향적봉", "계룡산천황봉"
        pattern = re.compile("^" + re.escape(name) + r"(\s*\(.+\)|\s*\S*(봉|대|정상))?$")
        found = []
        for n in nodes.values():
            t = n.get("tags", {})
            names = {t.get("name", "").strip(), t.get("name:ko", "").strip()}
            if not any(pattern.match(x) for x in names if x):
                continue
            ele = parse_ele(t)
            if ele is None or abs(ele - height) > PEAK_HEIGHT_TOLERANCE_M:
                continue
            if in_sido(n["lat"], n["lon"], sido):
                found.append(n)
        if len(found) == 1:
            n = found[0]
            point, source = (n["lat"], n["lon"]), f"osm node {n['id']} {n['tags'].get('name')} ele {n['tags'].get('ele')}"
            if tag in overrides:
                print(f"note: override for {tag} unused (OSM match node {n['id']})", file=sys.stderr)
        elif tag in overrides:
            used_overrides.add(tag)
            point, node_id, note = overrides[tag]
            why = "no OSM match" if not found else f"{len(found)} OSM matches"
            source = f"override ({why}) {node_id} {note}".strip()
        else:
            ids = ", ".join(str(n["id"]) for n in found) or "none"
            failures.append(f"{name} ({r['sido']}, {r['height_m']} m, {tag}): OSM candidates {ids}")
            continue
        peaks.append({"kind": KIND_PEAK, "tag": tag, "group": PEAK_GROUP, "route_index": None,
                      "lines": [quantize([point])]})
        summary.append(("PEAK", tag, 1, 0.0, source))
    if failures:
        print("unresolved peaks (add them to sources/peak-overrides.tsv):", file=sys.stderr)
        for f_ in failures:
            print("  " + f_, file=sys.stderr)
        sys.exit(1)
    for tag in sorted(set(overrides) - used_overrides - set(tags)):
        print(f"note: override {tag} matches no mountain in the csv", file=sys.stderr)
    return peaks, summary


# ---------------------------------------------------------------- main

def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--download", action="store_true", help="fetch sources/osm/*.json from Overpass first")
    ap.add_argument("--out", type=Path, default=DEFAULT_OUT, help=f"output directory (default {DEFAULT_OUT})")
    args = ap.parse_args()
    if args.download:
        download(QUERIES)

    route_elems, d1 = load_osm("routes")
    center_elems, d2 = load_osm("centers")
    peak_elems, d3 = load_osm("peaks")
    built_on = max(d1, d2, d3)  # OSM data date keeps rebuilds byte-identical
    if not re.fullmatch(r"\d{4}-\d{2}-\d{2}", built_on):
        sys.exit("cannot read timestamp_osm_base from the OSM files")

    routes, route_summary = build_routes(route_elems)
    centers, center_summary = build_centers(center_elems, routes)
    peaks, peak_summary = build_peaks(peak_elems)
    places = routes + centers + peaks  # routes first, so a route's index is its place index
    data = encode(built_on, places)

    national = sum(1 for r in routes if r["group"] == NATIONAL_GROUP)
    dropped = [s for s in route_summary if s[4].startswith("DROPPED")]
    print(f"built on {built_on}: {len(routes)} routes ({national} national), "
          f"{len(centers)} centres, {len(peaks)} peaks, {len(data)} bytes")
    for s in dropped:
        print(f"  DROPPED {s[1]}")
    if len(data) >= MAX_BYTES:
        sys.exit(f"places.bin is {len(data)} bytes, limit {MAX_BYTES}")

    args.out.mkdir(parents=True, exist_ok=True)
    (args.out / "places.bin").write_bytes(data)
    with open(args.out / "places-summary.tsv", "w", encoding="utf-8", newline="") as f:
        f.write("kind\ttag\tpoints\tkm\tsource\n")
        for kind, tag, points, km, source in route_summary + center_summary + peak_summary:
            f.write(f"{kind}\t{tag}\t{points}\t{km:.1f}\t{source}\n")
    print(f"wrote {args.out / 'places.bin'} and places-summary.tsv")


if __name__ == "__main__":
    main()
