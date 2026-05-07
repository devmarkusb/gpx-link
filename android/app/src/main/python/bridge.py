"""Kotlin ↔ Python bridge: reuse desktop HTML pipeline from ``gpx_link``."""

from __future__ import annotations

import json
from pathlib import Path

from gpx_link.bounds import bounds_for_map
from gpx_link.html_map import build_leaflet_html
from gpx_link.parser import load_map_features_from_paths


def _parse_map_view_tuple(map_view_json: str) -> tuple[float, float, int] | None:
    try:
        raw = json.loads(map_view_json)
    except json.JSONDecodeError:
        return None
    if not isinstance(raw, list) or len(raw) < 3:
        return None
    try:
        return (
            float(raw[0]),
            float(raw[1]),
            int(round(float(raw[2]))),
        )
    except (TypeError, ValueError):
        return None


def render(paths_json: str, map_view_json: str = "null") -> str:
    """Build Leaflet HTML for absolute filesystem paths (JSON string array).

    ``map_view_json``: JSON ``null`` or ``[lat, lng, zoom]`` from the WebView.
    When a valid triple is passed, the map keeps that pan/zoom (layers only
    update). When ``null``, visible GPX is fitted the first time like a fresh
    load.

    Returns a JSON object string:
    - ``{"ok": true, "html": "...", "warn_empty": bool}``
    - ``{"ok": false, "error": "..."}``
    """
    try:
        raw_paths = json.loads(paths_json)
        paths = [Path(str(p)) for p in raw_paths]
    except Exception as e:
        return json.dumps({"ok": False, "error": str(e)})

    map_center_and_zoom = _parse_map_view_tuple(map_view_json)
    preserve = map_center_and_zoom is not None

    if not paths:
        if preserve:
            html = build_leaflet_html(
                [],
                [],
                fit_padded_bounds=None,
                map_center_and_zoom=map_center_and_zoom,
            )
        else:
            html = build_leaflet_html([])
        return json.dumps({"ok": True, "html": html, "warn_empty": False})

    try:
        waypoints, geo_paths = load_map_features_from_paths(paths)
    except Exception as e:
        return json.dumps({"ok": False, "error": str(e)})

    warn_empty = not waypoints and not geo_paths
    if preserve:
        html = build_leaflet_html(
            waypoints,
            geo_paths,
            fit_padded_bounds=None,
            map_center_and_zoom=map_center_and_zoom,
        )
    else:
        html = build_leaflet_html(waypoints, geo_paths)
    return json.dumps({"ok": True, "html": html, "warn_empty": warn_empty})


def fit_bounds_corners(paths_json: str) -> str:
    """Corners for ``map.fitBounds`` for the current path list (checked files).

    Returns JSON ``{"ok": true, "corners": [[minLat,minLon],[maxLat,maxLon]]}``
    or ``{"ok": false, "error": "..."}``.
    """
    try:
        raw_paths = json.loads(paths_json)
        paths = [Path(str(p)) for p in raw_paths]
        if not paths:
            return json.dumps({"ok": False, "error": "no_paths"})
        waypoints, geo_paths = load_map_features_from_paths(paths)
        b = bounds_for_map(waypoints, geo_paths)
        if b is None:
            return json.dumps({"ok": False, "error": "no_coordinates"})
        padded = b.padded()
        corners: list[list[float]] = [
            [padded.min_lat, padded.min_lon],
            [padded.max_lat, padded.max_lon],
        ]
        return json.dumps({"ok": True, "corners": corners})
    except Exception as e:
        return json.dumps({"ok": False, "error": str(e)})
