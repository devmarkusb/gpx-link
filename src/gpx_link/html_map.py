from __future__ import annotations

import json
from collections.abc import Mapping
from typing import Any

from gpx_link.bounds import Bounds, bounds_for_map
from gpx_link.maps_urls import google_maps_url
from gpx_link.models import GeoPath, Waypoint
from gpx_link.simplify import simplify_polyline_points

_LEAFLET_CSS = "https://cdn.jsdelivr.net/npm/leaflet@1.9.4/dist/leaflet.css"
_LEAFLET_JS = "https://cdn.jsdelivr.net/npm/leaflet@1.9.4/dist/leaflet.js"

# Mobile WebView: huge polylines inflate HTML/JS parse; many DOM markers tank FPS.
_MAX_VERTICES_PER_LINE = 7000
_EPSILON_M_TRACK = 14.0

# Default: derive fit bounds from all waypoints and paths (CLI / Android / tests).
_USE_BOUNDS_FROM_FEATURES = object()


def _json_for_script(data: object) -> str:
    """Make JSON safe to embed in <script> (no raw ``</script>`` text)."""
    return json.dumps(data, separators=(",", ":")).replace("<", "\\u003c")


def build_map_js_payload(
    waypoints: list[Waypoint],
    paths: list[GeoPath] | None = None,
    *,
    fit_padded_bounds: Bounds | None | object = _USE_BOUNDS_FROM_FEATURES,
    map_center_and_zoom: tuple[float, float, int] | None = None,
) -> dict[str, Any]:
    """Serializable map state for ``gpxLinkApplyPayload``.

    For use from Qt ``runJavaScript`` or static HTML embedding.

    ``fit_padded_bounds``:
    - default sentinel: ``fitBounds`` from all waypoints and paths (padded).
    - a ``Bounds`` instance: use that box as ``fitBounds`` (caller pads if desired).
    - ``None``: no ``fitBounds``; use ``initialView`` if set, otherwise JS leaves
      pan/zoom unchanged (only layers update).
    """
    geopaths = paths or []
    markers: list[dict[str, object]] = []
    for w in waypoints:
        markers.append(
            {
                "lat": w.latitude,
                "lon": w.longitude,
                "name": w.name,
                "source": str(w.source_path),
                "gmaps": google_maps_url(w.latitude, w.longitude, name=w.name),
            }
        )
    line_features: list[dict[str, object]] = []
    for p in geopaths:
        coords = simplify_polyline_points(
            p.points,
            epsilon_m=_EPSILON_M_TRACK,
            max_points=_MAX_VERTICES_PER_LINE,
        )
        line_features.append(
            {
                "name": p.name,
                "kind": p.kind,
                "source": str(p.source_path),
                "coords": [[lat, lon] for lat, lon in coords],
            }
        )

    if fit_padded_bounds is _USE_BOUNDS_FROM_FEATURES:
        bounds = bounds_for_map(waypoints, geopaths)
        padded: Bounds | None = bounds.padded() if bounds else None
    elif isinstance(fit_padded_bounds, Bounds):
        padded = fit_padded_bounds
    else:
        padded = None

    fit: list[list[float]] | None
    if padded:
        fit = [
            [padded.min_lat, padded.min_lon],
            [padded.max_lat, padded.max_lon],
        ]
    else:
        fit = None

    initial_view: list[float] | None
    if map_center_and_zoom is not None:
        lat, lon, z = map_center_and_zoom
        initial_view = [lat, lon, float(z)]
    else:
        initial_view = None

    return {
        "markers": markers,
        "paths": line_features,
        "fitBounds": fit,
        "initialView": initial_view,
    }


def _leaflet_html_document(*, auto_apply_payload_literal: str | None) -> str:
    """Leaflet shell with ``gpxLinkApplyPayload``; optional initial payload literal."""
    tail = (
        f"\n    gpxLinkApplyPayload({auto_apply_payload_literal});\n"
        if auto_apply_payload_literal is not None
        else ""
    )
    return f"""<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <link rel="stylesheet" href="{_LEAFLET_CSS}" />
  <style>
    html, body, #map {{ height: 100%; margin: 0; }}
    body {{ font-family: system-ui, sans-serif; }}
  </style>
</head>
<body>
  <div id="map"></div>
  <script src="{_LEAFLET_JS}"></script>
  <script>
    function escHtml(text) {{
      return String(text)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;');
    }}
    const pathStyle = {{
      track: {{ color: '#2563eb', weight: 4, opacity: 0.85 }},
      route: {{ color: '#16a34a', weight: 4, opacity: 0.85 }},
    }};
    const waypointStyle = {{
      radius: 5,
      color: '#1d4ed8',
      weight: 1,
      fillColor: '#3b82f6',
      fillOpacity: 0.9,
    }};
    const map = L.map('map', {{ preferCanvas: true }});
    L.tileLayer('https://{{s}}.tile.openstreetmap.org/{{z}}/{{x}}/{{y}}.png', {{
      maxZoom: 19,
      attribution: '&copy; OpenStreetMap contributors'
    }}).addTo(map);
    map.setView([20, 0], 2);

    window.gpxLinkApplyPayload = function (payload) {{
      if (window._gpxContentGroup) {{
        map.removeLayer(window._gpxContentGroup);
      }}
      window._gpxContentGroup = L.featureGroup();
      const markers = payload.markers || [];
      const paths = payload.paths || [];
      for (const m of markers) {{
        const marker = L.circleMarker([m.lat, m.lon], waypointStyle).addTo(
          window._gpxContentGroup,
        );
        marker.bindTooltip(escHtml(m.name), {{ sticky: true }});
        marker.on('click', function () {{
          window.open(m.gmaps, '_blank', 'noopener,noreferrer');
        }});
      }}
      for (const p of paths) {{
        const style = pathStyle[p.kind] || pathStyle.track;
        const coords = p.coords;
        const layer =
          coords.length >= 2
            ? L.polyline(coords, style).addTo(window._gpxContentGroup)
            : L.circleMarker(coords[0], {{
                radius: 6,
                color: style.color,
                weight: 2,
                fillColor: style.color,
                fillOpacity: 0.55,
              }}).addTo(window._gpxContentGroup);
        const tip =
          escHtml(p.name) +
          '<br /><span style=\"opacity:.75;font-size:.85em;\">' +
          escHtml(p.kind) +
          '</span>';
        layer.bindTooltip(tip, {{ sticky: true }});
      }}
      window._gpxContentGroup.addTo(map);
      const fitBounds = payload.fitBounds;
      const initialView = payload.initialView;
      if (fitBounds) {{
        map.fitBounds(fitBounds);
      }} else if (initialView) {{
        map.setView([initialView[0], initialView[1]], initialView[2]);
      }}
    }};

    let userLocMarker = null;
    window.gpxLinkSetUserLocation = function (lat, lng, label) {{
      if (userLocMarker) {{
        map.removeLayer(userLocMarker);
      }}
      const tip = label ? escHtml(label) : 'Current location';
      userLocMarker = L.marker([lat, lng]).addTo(map);
      userLocMarker.bindTooltip(tip, {{ sticky: true }});
      const z = Math.max(map.getZoom(), 15);
      map.setView([lat, lng], z);
    }};
{tail}  </script>
</body>
</html>
"""


def build_leaflet_map_shell_html() -> str:
    """WebEngine shell: load Leaflet once; apply updates via ``gpxLinkApplyPayload``."""
    return _leaflet_html_document(auto_apply_payload_literal=None)


def build_leaflet_html(
    waypoints: list[Waypoint],
    paths: list[GeoPath] | None = None,
    *,
    fit_padded_bounds: Bounds | None | object = _USE_BOUNDS_FROM_FEATURES,
    map_center_and_zoom: tuple[float, float, int] | None = None,
) -> str:
    """Standalone HTML with Leaflet OSM, waypoint markers, and track/route paths."""
    payload = build_map_js_payload(
        waypoints,
        paths,
        fit_padded_bounds=fit_padded_bounds,
        map_center_and_zoom=map_center_and_zoom,
    )
    return _leaflet_html_document(
        auto_apply_payload_literal=_json_for_script(payload),
    )


def map_js_payload_literal(payload: Mapping[str, Any]) -> str:
    """JSON literal safe for ``runJavaScript`` wrapping ``gpxLinkApplyPayload``."""
    return _json_for_script(dict(payload))


__all__ = [
    "build_leaflet_html",
    "build_leaflet_map_shell_html",
    "build_map_js_payload",
    "map_js_payload_literal",
]
