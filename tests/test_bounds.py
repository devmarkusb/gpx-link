from __future__ import annotations

from pathlib import Path

from gpx_link.bounds import (
    bounds_for_map,
    bounds_for_tracks_from_file,
    bounds_for_waypoints,
)
from gpx_link.models import GeoPath, Waypoint


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


def test_bounds_for_paths_without_waypoints() -> None:
    gp = GeoPath(
        Path("/t.gpx"),
        "Line",
        "track",
        ((1.0, 2.0), (4.0, 6.0)),
    )
    b = bounds_for_map([], [gp])
    assert b is not None
    assert b.min_lat == 1.0 and b.max_lat == 4.0
    assert b.min_lon == 2.0 and b.max_lon == 6.0


def test_bounds_for_tracks_from_file_ignores_other_files_and_routes() -> None:
    a = Path("/a.gpx")
    b = Path("/b.gpx")
    tracks_a = GeoPath(a, "T", "track", ((0.0, 0.0), (1.0, 1.0)))
    route_a = GeoPath(a, "R", "route", ((5.0, 5.0), (6.0, 6.0)))
    tracks_b = GeoPath(b, "T2", "track", ((10.0, 10.0), (11.0, 11.0)))
    assert bounds_for_tracks_from_file(
        [tracks_a, route_a, tracks_b], a
    ) == bounds_for_map([], [tracks_a])
