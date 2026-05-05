from __future__ import annotations

import json

from gpx_link.bounds import Bounds, bounds_for_map
from gpx_link.maps_urls import google_maps_url
from gpx_link.models import GeoPath, Waypoint
from gpx_link.simplify import simplify_polyline_points

_LEAFLET_CSS = "https://cdn.jsdelivr.net/npm/leaflet@1.9.4/dist/leaflet.css"
_LEAFLET_JS = "https://cdn.jsdelivr.net/npm/leaflet@1.9.4/dist/leaflet.js"

# Mobile WebView: huge polylines inflate HTML/JS parse; many DOM markers tank FPS.
_MAX_VERTICES_PER_LINE = 7000
_EPSILON_M_TRACK = 14.0


def _json_for_script(data: object) -> str:
    """Make JSON safe to embed in <script> (no raw ``</script>`` text)."""
    return json.dumps(data, separators=(",", ":")).replace("<", "\\u003c")


def build_leaflet_html(
    waypoints: list[Waypoint],
    paths: list[GeoPath] | None = None,
) -> str:
    """Standalone HTML with Leaflet OSM, waypoint markers, and track/route paths."""
    geopaths = paths or []
    bounds = bounds_for_map(waypoints, geopaths)
    padded: Bounds | None = bounds.padded() if bounds else None
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
    markers_json = _json_for_script(markers)
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
    paths_json = _json_for_script(line_features)
    if padded:
        fit = _json_for_script(
            [
                [padded.min_lat, padded.min_lon],
                [padded.max_lat, padded.max_lon],
            ]
        )
    else:
        fit = "null"

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
    const markers = {markers_json};
    const paths = {paths_json};
    const fitBounds = {fit};
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
    if (fitBounds) {{
      map.fitBounds(fitBounds);
    }} else {{
      map.setView([20, 0], 2);
    }}
    for (const m of markers) {{
      const marker = L.circleMarker([m.lat, m.lon], waypointStyle).addTo(map);
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
          ? L.polyline(coords, style).addTo(map)
          : L.circleMarker(coords[0], {{
              radius: 6,
              color: style.color,
              weight: 2,
              fillColor: style.color,
              fillOpacity: 0.55,
            }}).addTo(map);
      const tip =
        escHtml(p.name) +
        '<br /><span style=\"opacity:.75;font-size:.85em;\">' +
        escHtml(p.kind) +
        '</span>';
      layer.bindTooltip(tip, {{ sticky: true }});
    }}
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
  </script>
</body>
</html>
"""
