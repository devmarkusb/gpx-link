"""Kotlin ↔ Python bridge: reuse desktop HTML pipeline from ``gpx_link``."""

from __future__ import annotations

import json
from pathlib import Path

from gpx_link.html_map import build_leaflet_html
from gpx_link.parser import load_map_features_from_paths


def render(paths_json: str) -> str:
    """Build Leaflet HTML for absolute filesystem paths (JSON string array).

    Returns a JSON object string:
    - ``{"ok": true, "html": "...", "warn_empty": bool}``
    - ``{"ok": false, "error": "..."}``
    """
    try:
        raw_paths = json.loads(paths_json)
        paths = [Path(str(p)) for p in raw_paths]
        if not paths:
            return json.dumps(
                {"ok": True, "html": build_leaflet_html([]), "warn_empty": False}
            )
        waypoints, geo_paths = load_map_features_from_paths(paths)
        warn_empty = not waypoints and not geo_paths
        html = build_leaflet_html(waypoints, geo_paths)
        return json.dumps({"ok": True, "html": html, "warn_empty": warn_empty})
    except OSError as e:
        return json.dumps({"ok": False, "error": str(e)})
