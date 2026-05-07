from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from gpx_link.html_map import build_leaflet_html
from gpx_link.parser import load_map_features_from_paths


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        prog="gpx-link",
        description="List GPX waypoints and export an OpenStreetMap HTML view.",
    )
    sub = parser.add_subparsers(dest="command", required=True)

    p_list = sub.add_parser("list", help="Print waypoints as JSON lines")
    p_list.add_argument(
        "gpx",
        nargs="+",
        type=Path,
        help="GPX file path(s)",
    )

    p_html = sub.add_parser("html", help="Write Leaflet + OSM HTML to stdout or file")
    p_html.add_argument(
        "gpx",
        nargs="+",
        type=Path,
        help="GPX file path(s)",
    )
    p_html.add_argument(
        "-o",
        "--output",
        type=Path,
        help="Output file (default: stdout)",
    )

    args = parser.parse_args(argv)
    paths = [p.expanduser().resolve() for p in args.gpx]

    if args.command == "list":
        wpts, _ = load_map_features_from_paths(paths)
        for w in wpts:
            line = {
                "source": str(w.source_path),
                "name": w.name,
                "latitude": w.latitude,
                "longitude": w.longitude,
                "elevation_m": w.elevation_m,
                "description": w.description,
                "symbol": w.symbol,
                "waypoint_type": w.waypoint_type,
            }
            sys.stdout.write(json.dumps(line, ensure_ascii=False) + "\n")
        return 0

    if args.command == "html":
        wpts, geopaths = load_map_features_from_paths(paths)
        html = build_leaflet_html(wpts, geopaths)
        if args.output:
            args.output.write_text(html, encoding="utf-8")
        else:
            sys.stdout.write(html)
        return 0

    return 1
