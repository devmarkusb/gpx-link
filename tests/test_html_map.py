from __future__ import annotations

from pathlib import Path

from gpx_link.html_map import build_leaflet_html
from gpx_link.maps_urls import google_maps_url
from gpx_link.models import GeoPath, Waypoint


def test_build_leaflet_html_contains_osm_and_fit_bounds() -> None:
    wpts = [
        Waypoint(Path("/a.gpx"), "A", 45.5, -122.5),
        Waypoint(Path("/b.gpx"), "B", 46.0, -123.0),
    ]
    html = build_leaflet_html(wpts)
    assert "function escHtml" in html
    assert "L.Util.escapeHTML" not in html
    assert "tile.openstreetmap.org" in html
    assert "leaflet" in html.lower()
    assert "45.5" in html and "-122.5" in html
    assert "map.fitBounds" in html
    assert google_maps_url(45.5, -122.5, name="A") in html
    assert google_maps_url(46.0, -123.0, name="B") in html


def test_build_leaflet_html_empty_world_view() -> None:
    html = build_leaflet_html([])
    assert "map.setView" in html
    assert "tile.openstreetmap.org" in html


def test_build_leaflet_html_renders_track_polyline() -> None:
    line = GeoPath(
        Path("/x.gpx"),
        "Trail",
        "track",
        ((45.51, -122.5), (45.52, -122.51)),
    )
    html = build_leaflet_html([], [line])
    assert "L.polyline" in html
    assert "45.51" in html and "-122.5" in html
    assert "map.fitBounds" in html
