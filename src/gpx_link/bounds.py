from __future__ import annotations

from dataclasses import dataclass

from gpx_link.models import GeoPath, Waypoint

# Minimum span so a single point still gets a sensible zoom (degrees).
_MIN_SPAN_DEG = 0.002
_DEFAULT_PAD_RATIO = 0.12


@dataclass(frozen=True, slots=True)
class Bounds:
    min_lat: float
    max_lat: float
    min_lon: float
    max_lon: float

    def padded(self, pad_ratio: float = _DEFAULT_PAD_RATIO) -> Bounds:
        lat_span = max(self.max_lat - self.min_lat, _MIN_SPAN_DEG)
        lon_span = max(self.max_lon - self.min_lon, _MIN_SPAN_DEG)
        plat = lat_span * pad_ratio
        plon = lon_span * pad_ratio
        return Bounds(
            min_lat=self.min_lat - plat,
            max_lat=self.max_lat + plat,
            min_lon=self.min_lon - plon,
            max_lon=self.max_lon + plon,
        )


def bounds_for_waypoints(waypoints: list[Waypoint]) -> Bounds | None:
    """Return axis-aligned bounds for all waypoints, or None if empty."""
    return bounds_for_map(waypoints, [])


def bounds_for_map(
    waypoints: list[Waypoint],
    paths: list[GeoPath],
) -> Bounds | None:
    """Bounding box over waypoints and path vertices; None if both are empty."""
    lats: list[float] = []
    lons: list[float] = []
    for w in waypoints:
        lats.append(w.latitude)
        lons.append(w.longitude)
    for p in paths:
        for lat, lon in p.points:
            lats.append(lat)
            lons.append(lon)
    if not lats:
        return None
    return Bounds(
        min_lat=min(lats),
        max_lat=max(lats),
        min_lon=min(lons),
        max_lon=max(lons),
    )
