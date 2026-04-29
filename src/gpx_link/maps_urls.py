from __future__ import annotations

from urllib.parse import quote

# Coordinate-first URL: drops a pin at the exact waypoint; user can inspect
# nearby POIs in the Google Maps UI. Text-only search from the waypoint name
# is ambiguous and can resolve to the wrong place, so we avoid it by default.
_GOOGLE_MAPS_SEARCH = "https://www.google.com/maps/search/?api=1&query="


def google_maps_url(latitude: float, longitude: float) -> str:
    """Build a Google Maps URL focused on the precise coordinates.

    Uses the Maps URL API search endpoint with a ``lat,lng`` query so the map
    selection matches the GPX waypoint.
    """
    q = f"{latitude:.7f},{longitude:.7f}"
    return f"{_GOOGLE_MAPS_SEARCH}{quote(q, safe='')}"
