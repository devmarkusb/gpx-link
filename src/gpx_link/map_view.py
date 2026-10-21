"""Decode viewport state shared by the desktop and Android map hosts."""

from __future__ import annotations

import json
import math

MIN_MAP_ZOOM = 0
MAX_MAP_ZOOM = 19


def parse_map_view(raw: object) -> tuple[float, float, int] | None:
    """Return a supported latitude, longitude, zoom triple, or ignore invalid state.

    Latitude must be within ±90 and zoom within the map's 0–19 range.
    Longitude may exceed ±180 when the user pans across Leaflet's wrapped world.
    """
    if isinstance(raw, str):
        try:
            raw = json.loads(raw)
        except ValueError:
            return None
    if not isinstance(raw, (list, tuple)) or len(raw) < 3:
        return None
    try:
        lat, lon, zoom = (float(value) for value in raw[:3])
        if not all(math.isfinite(value) for value in (lat, lon, zoom)):
            return None
        if not -90 <= lat <= 90 or not MIN_MAP_ZOOM <= zoom <= MAX_MAP_ZOOM:
            return None
        return lat, lon, int(round(zoom))
    except (TypeError, ValueError, OverflowError):
        return None
