from __future__ import annotations

from pathlib import Path

from gpx_link.html_map import (
    build_leaflet_html,
    build_leaflet_map_shell_html,
    build_map_js_payload,
)
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
    assert "preferCanvas" in html
    assert "fadeAnimation" in html
    assert "vectorRenderer" in html
    assert "L.divIcon" in html
    assert "gpx-poi-inner" in html
    assert "GPX_DOM_POI_LIMIT" in html
    assert "GPX_POI_AUTO_TOOLTIP_LIMIT" in html
    assert "GPX_POI_VIEW_PAD" in html
    assert "refreshGpxPoiMarkers" in html
    assert "L.circleMarker" in html
    assert "poiVisual" in html
    assert "gpxLinkSetUserLocation" in html
    assert "gpxLinkApplyPayload" in html
    assert "labelMode" in html
    assert google_maps_url(45.5, -122.5, name="A") in html
    assert google_maps_url(46.0, -123.0, name="B") in html


def test_build_leaflet_html_has_base_layer_toggle() -> None:
    html = build_leaflet_html([])
    assert "tile.openstreetmap.org" in html
    assert "tile.opentopomap.org" in html
    assert "BaseToggleControl" in html
    assert "gpx-base-toggle" in html


def test_build_leaflet_html_empty_world_view() -> None:
    html = build_leaflet_html([])
    assert "center: [20.0, 0.0]" in html
    assert "zoom: 2" in html
    assert "tile.openstreetmap.org" in html


def test_build_leaflet_html_fit_bounds_keeps_world_bootstrap() -> None:
    wpts = [Waypoint(Path("/a.gpx"), "A", 45.5, -122.5)]
    html = build_leaflet_html(wpts)
    assert "center: [20.0, 0.0]" in html
    assert "zoom: 2" in html
    assert "map.fitBounds" in html


def test_build_leaflet_html_saved_view_bootstraps_map() -> None:
    html = build_leaflet_html(
        [],
        fit_padded_bounds=None,
        map_center_and_zoom=(48.85, 2.35, 11),
    )
    assert "center: [48.85, 2.35]" in html
    assert "zoom: 11" in html


def test_build_leaflet_map_shell_has_apply_but_no_initial_coords() -> None:
    html = build_leaflet_map_shell_html()
    assert "gpxLinkApplyPayload" in html
    assert "45.51" not in html


def test_build_map_js_payload_track_coords() -> None:
    line = GeoPath(
        Path("/x.gpx"),
        "Trail",
        "track",
        ((45.51, -122.5), (45.52, -122.51)),
    )
    payload = build_map_js_payload([], [line])
    assert payload["fitBounds"] is not None
    assert payload["initialView"] is None
    coords = payload["paths"][0]["coords"]
    assert len(coords) >= 2


def test_build_map_js_payload_marker_labels_coerced() -> None:
    payload = build_map_js_payload([], [], marker_labels="on")
    assert payload["markerLabels"] == "on"
    payload2 = build_map_js_payload([], [], marker_labels="bogus")
    assert payload2["markerLabels"] == "auto"


def test_build_leaflet_html_embeds_marker_labels() -> None:
    html = build_leaflet_html([], marker_labels="off")
    assert '"markerLabels":"off"' in html.replace(" ", "")


def test_build_map_js_payload_saved_view_without_fit() -> None:
    payload = build_map_js_payload(
        [],
        [],
        fit_padded_bounds=None,
        map_center_and_zoom=(48.85, 2.35, 11),
    )
    assert payload["fitBounds"] is None
    assert payload["initialView"] == [48.85, 2.35, 11.0]
    assert payload["markerLabels"] == "auto"


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
