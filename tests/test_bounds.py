from __future__ import annotations

from pathlib import Path

from gpx_link.bounds import bounds_for_waypoints
from gpx_link.models import Waypoint


def _w(lat: float, lon: float) -> Waypoint:
    return Waypoint(
        source_path=Path("/x.gpx"),
        name="x",
        latitude=lat,
        longitude=lon,
    )


def test_bounds_for_waypoints_empty() -> None:
    assert bounds_for_waypoints([]) is None


def test_bounds_single_point_padded_span() -> None:
    b = bounds_for_waypoints([_w(1.0, 2.0)])
    assert b is not None
    p = b.padded()
    assert p.min_lat < p.max_lat
    assert p.min_lon < p.max_lon


def test_bounds_multiple() -> None:
    b = bounds_for_waypoints([_w(0.0, 0.0), _w(10.0, 5.0)])
    assert b is not None
    assert b.min_lat == 0.0 and b.max_lat == 10.0
    assert b.min_lon == 0.0 and b.max_lon == 5.0
