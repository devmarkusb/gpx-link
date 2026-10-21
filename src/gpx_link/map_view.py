"""Decode viewport state shared by the desktop and Android map hosts."""

from __future__ import annotations

import json
import math


def parse_map_view(raw: object) -> tuple[float, float, int] | None:
    """Return a finite latitude, longitude, zoom triple, or ignore invalid state.

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
        return lat, lon, int(round(zoom))
    except (TypeError, ValueError, OverflowError):
        return None
