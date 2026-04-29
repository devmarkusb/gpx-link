from __future__ import annotations

import json

from gpx_link.bounds import Bounds, bounds_for_waypoints
from gpx_link.maps_urls import google_maps_url
from gpx_link.models import Waypoint

_LEAFLET_CSS = "https://cdn.jsdelivr.net/npm/leaflet@1.9.4/dist/leaflet.css"
_LEAFLET_JS = "https://cdn.jsdelivr.net/npm/leaflet@1.9.4/dist/leaflet.js"


def build_leaflet_html(waypoints: list[Waypoint]) -> str:
    """Return standalone HTML with Leaflet OSM and waypoint markers."""
    bounds = bounds_for_waypoints(waypoints)
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
    const markers = {markers_json};
    const fitBounds = {fit};
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
      marker.bindTooltip(L.Util.escapeHTML(m.name), {{ sticky: true }});
      marker.on('click', function () {{
        window.open(m.gmaps, '_blank', 'noopener,noreferrer');
      }});
    }}
  </script>
</body>
</html>
"""
