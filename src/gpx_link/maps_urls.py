from __future__ import annotations

from urllib.parse import quote

_GOOGLE_MAPS_SEARCH = "https://www.google.com/maps/search/?api=1&query="
_MAP_ZOOM = 17


def _is_generic_waypoint_label(name: str) -> bool:
    n = name.strip().lower()
    return n == "" or n == "waypoint"


def google_maps_url(
    latitude: float,
    longitude: float,
    *,
    name: str | None = None,
) -> str:
    """Build a Google Maps URL for a waypoint.

    When the waypoint has a real place name, uses the Maps web URL shape
    ``/maps/search/{name}/@{lat},{lng},{z}`` so Google runs a place search
    centered on the GPX coordinates—typically surfacing and selecting the
    nearby POI that matches the name.

    For empty or generic names (e.g. parser default ``Waypoint``), falls back
    to the Maps URL API with a ``lat,lng`` query so the pin stays exact.
    """
    lat_s = f"{latitude:.7f}"
    lon_s = f"{longitude:.7f}"
    label = (name or "").strip()
    if label and not _is_generic_waypoint_label(label):
        q = quote(label, safe="")
        return f"https://www.google.com/maps/search/{q}/@{lat_s},{lon_s},{_MAP_ZOOM}z"
    coord_q = f"{lat_s},{lon_s}"
    return f"{_GOOGLE_MAPS_SEARCH}{quote(coord_q, safe='')}"
