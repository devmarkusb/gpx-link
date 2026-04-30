from __future__ import annotations

import json

from gpx_link.bounds import Bounds, bounds_for_map
from gpx_link.maps_urls import google_maps_url
from gpx_link.models import GeoPath, Waypoint

_LEAFLET_CSS = "https://cdn.jsdelivr.net/npm/leaflet@1.9.4/dist/leaflet.css"
_LEAFLET_JS = "https://cdn.jsdelivr.net/npm/leaflet@1.9.4/dist/leaflet.js"


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
                "gmaps": google_maps_url(w.latitude, w.longitude),
            }
        )
    markers_json = json.dumps(markers)
    line_features: list[dict[str, object]] = []
    for p in geopaths:
        line_features.append(
            {
                "name": p.name,
                "kind": p.kind,
                "source": str(p.source_path),
                "coords": [[lat, lon] for lat, lon in p.points],
            }
        )
    paths_json = json.dumps(line_features)
    if padded:
        fit = json.dumps(
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
    const map = L.map('map');
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
      const marker = L.marker([m.lat, m.lon]).addTo(map);
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
  </script>
</body>
</html>
"""
