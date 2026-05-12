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

# Mobile WebView / Qt WebEngine: huge polylines inflate HTML/JS parse.
# DOM emoji markers are limited to when ≤ this many POIs lie in the map view
# (see JS); otherwise canvas dots for POIs currently in view.
_DOM_POI_MARKER_LIMIT = 1000
# When ≤ this many POIs are in the padded viewport, show name tooltips always
# (permanent) so users can read labels without clicking (click still opens Maps).
_POI_AUTO_TOOLTIP_LIMIT = 25
# Leaflet bounds.pad() — widen “in view” so edge markers do not flicker.
_POI_VIEW_PAD = 0.12
_MAX_VERTICES_PER_LINE = 5000
_EPSILON_M_TRACK = 18.0


def _coerce_marker_label_mode(raw: object) -> str:
    """Return ``on``, ``off``, or ``auto`` for waypoint name bubbles on the map."""
    if not isinstance(raw, str):
        return "auto"
    s = raw.strip().lower()
    if s in ("on", "off", "auto"):
        return s
    return "auto"


# Default: derive fit bounds from all waypoints and paths (CLI / Android / tests).
_USE_BOUNDS_FROM_FEATURES = object()

# First paint before ``gpxLinkApplyPayload`` runs during full WebView reloads.
_BOOTSTRAP_WORLD: tuple[float, float, int] = (20.0, 0.0, 2)


def _bootstrap_map_view_from_script_payload_literal(
    auto_apply_payload_literal: str | None,
) -> tuple[float, float, int]:
    """Pan/zoom for ``L.map`` when the document embeds an explicit saved view."""
    if auto_apply_payload_literal is None:
        return _BOOTSTRAP_WORLD
    try:
        payload = json.loads(auto_apply_payload_literal)
    except json.JSONDecodeError:
        return _BOOTSTRAP_WORLD
    iv = payload.get("initialView")
    if isinstance(iv, list) and len(iv) >= 3:
        try:
            return (
                float(iv[0]),
                float(iv[1]),
                int(round(float(iv[2]))),
            )
        except (TypeError, ValueError):
            pass
    return _BOOTSTRAP_WORLD


def _json_for_script(data: object) -> str:
    """Make JSON safe to embed in <script> (no raw ``</script>`` text)."""
    return json.dumps(data, separators=(",", ":")).replace("<", "\\u003c")


def build_map_js_payload(
    waypoints: list[Waypoint],
    paths: list[GeoPath] | None = None,
    *,
    fit_padded_bounds: Bounds | None | object = _USE_BOUNDS_FROM_FEATURES,
    map_center_and_zoom: tuple[float, float, int] | None = None,
    marker_labels: str = "auto",
) -> dict[str, Any]:
    """Serializable map state for ``gpxLinkApplyPayload``.

    For use from Qt ``runJavaScript`` or static HTML embedding.

    ``fit_padded_bounds``:
    - default sentinel: ``fitBounds`` from all waypoints and paths (padded).
    - a ``Bounds`` instance: use that box as ``fitBounds`` (caller pads if desired).
    - ``None``: no ``fitBounds``; use ``initialView`` if set, otherwise JS leaves
      pan/zoom unchanged (only layers update).

    ``marker_labels``: ``on`` (always show name bubbles for markers in view),
    ``off`` (hover only), or ``auto`` (bubbles when at most
    ``_POI_AUTO_TOOLTIP_LIMIT`` markers lie in the padded viewport).
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
                "symbol": w.symbol,
                "waypointType": w.waypoint_type,
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
        "markerLabels": _coerce_marker_label_mode(marker_labels),
    }


def _leaflet_html_document(*, auto_apply_payload_literal: str | None) -> str:
    """Leaflet shell with ``gpxLinkApplyPayload``; optional initial payload literal."""
    tail = (
        f"\n    gpxLinkApplyPayload({auto_apply_payload_literal});\n"
        if auto_apply_payload_literal is not None
        else ""
    )
    boot_lat, boot_lon, boot_zoom = _bootstrap_map_view_from_script_payload_literal(
        auto_apply_payload_literal
    )
    boot_lat_js = json.dumps(boot_lat)
    boot_lon_js = json.dumps(boot_lon)
    return f"""<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <link rel="stylesheet" href="{_LEAFLET_CSS}" />
  <style>
    html, body, #map {{ height: 100%; margin: 0; }}
    body {{ font-family: system-ui, sans-serif; }}
    .gpx-poi-marker {{
      background: none !important;
      border: none !important;
    }}
    .gpx-poi-inner {{
      width: 30px;
      height: 30px;
      border-radius: 50%;
      border: 2px solid #334155;
      background: #fff;
      display: flex;
      align-items: center;
      justify-content: center;
      font-size: 15px;
      line-height: 1;
      box-shadow: 0 1px 4px rgba(15, 23, 42, 0.35);
      box-sizing: border-box;
    }}
    .gpx-base-toggle {{
      background: #fff;
      border: 2px solid rgba(0, 0, 0, 0.2);
      border-radius: 6px;
      box-shadow: 0 1px 4px rgba(15, 23, 42, 0.25);
      cursor: pointer;
      font: 600 13px/1 system-ui, sans-serif;
      color: #1f2937;
      min-width: 64px;
      min-height: 44px;
      padding: 0 12px;
      box-sizing: border-box;
    }}
    .gpx-base-toggle:hover {{ background: #f1f5f9; }}
    .gpx-base-toggle:active {{ background: #e2e8f0; }}
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
    function hueFromStr(str) {{
      let h = 0;
      for (let i = 0; i < str.length; i++) {{
        h = ((h << 5) - h) + str.charCodeAt(i);
        h |= 0;
      }}
      return Math.abs(h) % 360;
    }}
    /** Map GPX sym / type strings to marker emoji and ring color (best-effort). */
    function poiVisual(symbol, waypointType, name) {{
      const combined = [symbol || '', waypointType || '', name || ''].join(' ').trim();
      const s = combined.toLowerCase();
      const rules = [
        [/\\bpharmacy\\b|drug\\s*store|\\bcvs\\b|\\bwalgreens\\b/i, '💊', '#7c3aed'],
        [/hospital|clinic|emergency|\\burgent\\b|\\bems\\b/i, '🏥', '#dc2626'],
        [/camp|tent|\\brv\\s*park/i, '⛺', '#ca8a04'],
        [
          /lodging|hotel|motel|hostel|resort|bed|\\bb&b\\b|b\\s*&\\s*b/i,
          '🛏️',
          '#4f46e5',
        ],
        [/gas|fuel|diesel|petrol|\\bgas\\s*station/i, '⛽', '#15803d'],
        [/parking|\\bpark\\s*lot/i, '🅿️', '#52525b'],
        [/restaurant|dining|food|cafe|coffee|bar|pub|bakery/i, '🍽️', '#c2410c'],
        [/grocery|supermarket|\\bmarket\\b|\\bmarketplace\\b/i, '🛒', '#059669'],
        [/\\bzoo\\b|\\bzoological|wildlife\\s*park/i, '🦁', '#b45309'],
        [
          /aquarium|oceanarium|sea\\s*life|auqarium|auqrium/i,
          '🐠',
          '#0e7490',
        ],
        [/playground|\\bplay\\s*area/i, '🛝', '#ea580c'],
        [
          /swimming|outdoor\\s*pool|lap\\s*pool|natatorium|\\bpool\\b|aquatic\\s*center|wading\\s*pool|water\\s*park/i,
          '🏊',
          '#0284c7',
        ],
        [/water|drinking|\\bfountain\\b|\\bhydration/i, '💧', '#0ea5e9'],
        [/view|scenic|overlook|vista|panorama/i, '👁️', '#7c3aed'],
        [/summit|peak|mountain|\\bpeak\\b|\\bmt\\.\\b/i, '⛰️', '#57534e'],
        [/trailhead|trail\\s*head|\\bhiking\\b|hike/i, '🥾', '#854d0e'],
        [/bridge/i, '🌉', '#0369a1'],
        [/waterfall|falls\\b/i, '💧', '#0284c7'],
        [/beach|ocean|coast|shore/i, '🏖️', '#0d9488'],
        [/airport|airfield|\\bhelipad\\b/i, '✈️', '#1d4ed8'],
        [/marina|harbor|harbour|boat|ferry/i, '⛵', '#2563eb'],
        [/fish/i, '🎣', '#0f766e'],
        [/ski|skiing|lift/i, '⛷️', '#7dd3fc'],
        [/danger|hazard|caution|warning/i, '⚠️', '#d97706'],
        [/(\\binfo\\b|information|visitor\\s*center)/i, 'ℹ️', '#2563eb'],
        [/(city|town|village|settlement|urban)/i, '🏙️', '#64748b'],
        [/church|chapel|cathedral|temple|mosque|shrine/i, '⛪', '#78716c'],
        [/cemetery|graveyard/i, '🪦', '#57534e'],
        [/picnic/i, '🧺', '#65a30d'],
        [/tunnel/i, '🚇', '#475569'],
        [/pass|col\\b|notch|saddle/i, '🚩', '#b45309'],
        [/ruin|historic|archaeology|monument|memorial|\\bcastle\\b/i, '🏛️', '#92400e'],
        [/geocache|\\bgc\\b/i, '🎯', '#a16207'],
        [/blue\\b|flag.*blue|diamond.*blue/i, '📍', '#2563eb'],
        [/green\\b|diamond.*green/i, '📍', '#16a34a'],
        [/red\\b|flag.*red/i, '📍', '#dc2626'],
        [/yellow|amber/i, '📍', '#ca8a04'],
        [/pin|pushpin|\\bflag\\b|waypoint|\\bwpt\\b/i, '📍', '#2563eb'],
      ];
      for (const row of rules) {{
        const re = row[0];
        const emoji = row[1];
        const color = row[2];
        if (re.test(s)) {{
          return {{ emoji: emoji, borderColor: color }};
        }}
      }}
      const hue = hueFromStr(combined || 'waypoint');
      return {{
        emoji: '📍',
        borderColor: 'hsl(' + hue + ', 52%, 42%)',
      }};
    }}
    function poiMarkerHtml(visual) {{
      const st = 'border-color:' + visual.borderColor;
      return '<div class="gpx-poi-inner" style="' + st + '">' + visual.emoji + '</div>';
    }}
    /** ``skipLeadingEmoji``: icon already shows emoji on the map (DOM markers). */
    function poiTooltipHtml(m, visual, skipLeadingEmoji) {{
      let out = skipLeadingEmoji
        ? escHtml(m.name)
        : (
            '<span style="font-size:1.15em;line-height:1">' +
            escHtml(visual.emoji) +
            '</span><br />' +
            escHtml(m.name)
          );
      const typ = (m.waypointType || '').trim();
      const sym = (m.symbol || '').trim();
      if (typ) {{
        out +=
          '<br /><span style="opacity:.85;font-size:.88em;">' +
          escHtml(typ) +
          '</span>';
      }}
      if (sym && sym.toLowerCase() !== typ.toLowerCase()) {{
        out +=
          '<br /><span style="opacity:.7;font-size:.82em;">' +
          escHtml(sym) +
          '</span>';
      }}
      return out;
    }}
    const map = L.map('map', {{
      preferCanvas: true,
      fadeAnimation: false,
      zoomAnimation: true,
      center: [{boot_lat_js}, {boot_lon_js}],
      zoom: {boot_zoom},
    }});
    const vectorRenderer = L.canvas({{ padding: 0.5 }});
    const baseLayers = {{
      osm: L.tileLayer('https://{{s}}.tile.openstreetmap.org/{{z}}/{{x}}/{{y}}.png', {{
        maxZoom: 19,
        fadeAnimation: false,
        attribution: '&copy; OpenStreetMap contributors'
      }}),
      topo: L.tileLayer('https://{{s}}.tile.opentopomap.org/{{z}}/{{x}}/{{y}}.png', {{
        maxZoom: 17,
        fadeAnimation: false,
        attribution:
          'Map data: &copy; OpenStreetMap contributors, SRTM | ' +
          'Map style: &copy; <a href="https://opentopomap.org">OpenTopoMap</a> ' +
          '(CC-BY-SA)'
      }}),
    }};
    let currentBaseKey = 'osm';
    baseLayers.osm.addTo(map);

    const BaseToggleControl = L.Control.extend({{
      options: {{ position: 'topright' }},
      onAdd: function () {{
        const btn = L.DomUtil.create('button', 'gpx-base-toggle');
        btn.type = 'button';
        btn.title = 'Toggle base map (Street / Topographic)';
        btn.setAttribute('aria-label', btn.title);
        btn.textContent = 'Topo';
        L.DomEvent.disableClickPropagation(btn);
        L.DomEvent.disableScrollPropagation(btn);
        L.DomEvent.on(btn, 'click', function (ev) {{
          L.DomEvent.stop(ev);
          const nextKey = currentBaseKey === 'osm' ? 'topo' : 'osm';
          map.removeLayer(baseLayers[currentBaseKey]);
          baseLayers[nextKey].addTo(map);
          currentBaseKey = nextKey;
          btn.textContent = nextKey === 'osm' ? 'Topo' : 'Map';
        }});
        return btn;
      }},
    }});
    new BaseToggleControl().addTo(map);

    const GPX_DOM_POI_LIMIT = {_DOM_POI_MARKER_LIMIT};
    const GPX_POI_AUTO_TOOLTIP_LIMIT = {_POI_AUTO_TOOLTIP_LIMIT};
    const GPX_POI_VIEW_PAD = {_POI_VIEW_PAD};
    function gpxDebounce(fn, waitMs) {{
      let tid = null;
      return function () {{
        clearTimeout(tid);
        const self = this;
        const args = arguments;
        tid = setTimeout(function () {{
          fn.apply(self, args);
        }}, waitMs);
      }};
    }}

    window.gpxLinkApplyPayload = function (payload) {{
      if (window._gpxPoiMoveListener) {{
        map.off('moveend', window._gpxPoiMoveListener);
        map.off('zoomend', window._gpxPoiMoveListener);
        window._gpxPoiMoveListener = null;
      }}
      if (window._gpxContentGroup) {{
        map.removeLayer(window._gpxContentGroup);
      }}
      window._gpxContentGroup = L.featureGroup();
      window._gpxMarkersData = payload.markers || [];
      window._gpxPoiLayerGroup = null;
      const paths = payload.paths || [];

      function refreshGpxPoiMarkers() {{
        const markers = window._gpxMarkersData;
        if (!markers.length || !window._gpxContentGroup) {{
          return;
        }}
        const bounds = map.getBounds().pad(GPX_POI_VIEW_PAD);
        let inViewCount = 0;
        for (let i = 0; i < markers.length; i++) {{
          const mm = markers[i];
          if (bounds.contains(L.latLng(mm.lat, mm.lon))) {{
            inViewCount += 1;
          }}
        }}
        const useDom = inViewCount <= GPX_DOM_POI_LIMIT;
        const modeRaw = String(payload.markerLabels || 'auto').toLowerCase();
        const labelMode =
          modeRaw === 'on' || modeRaw === 'off' ? modeRaw : 'auto';
        let poiTipsPermanent;
        if (labelMode === 'off') {{
          poiTipsPermanent = false;
        }} else if (labelMode === 'on') {{
          poiTipsPermanent = true;
        }} else {{
          poiTipsPermanent = inViewCount <= GPX_POI_AUTO_TOOLTIP_LIMIT;
        }}
        if (window._gpxPoiLayerGroup) {{
          window._gpxContentGroup.removeLayer(window._gpxPoiLayerGroup);
        }}
        window._gpxPoiLayerGroup = L.layerGroup();
        for (let i = 0; i < markers.length; i++) {{
          const m = markers[i];
          if (!bounds.contains(L.latLng(m.lat, m.lon))) {{
            continue;
          }}
          const vis = poiVisual(m.symbol, m.waypointType, m.name);
          let layer;
          if (useDom) {{
            const icon = L.divIcon({{
              className: 'gpx-poi-marker',
              html: poiMarkerHtml(vis),
              iconSize: [32, 32],
              iconAnchor: [16, 16],
            }});
            layer = L.marker([m.lat, m.lon], {{ icon: icon }});
          }} else {{
            layer = L.circleMarker([m.lat, m.lon], {{
              renderer: vectorRenderer,
              radius: 8,
              color: vis.borderColor,
              weight: 2,
              fillColor: '#ffffff',
              fillOpacity: 0.92,
            }});
          }}
          layer.bindTooltip(poiTooltipHtml(m, vis, useDom), {{
            sticky: false,
            direction: 'top',
            permanent: poiTipsPermanent,
          }});
          layer.on('click', function () {{
            window.open(m.gmaps, '_blank', 'noopener,noreferrer');
          }});
          layer.addTo(window._gpxPoiLayerGroup);
        }}
        window._gpxPoiLayerGroup.addTo(window._gpxContentGroup);
      }}

      const debouncedPoiRefresh = gpxDebounce(refreshGpxPoiMarkers, 48);
      window._gpxPoiMoveListener = debouncedPoiRefresh;
      map.on('moveend zoomend', window._gpxPoiMoveListener);

      for (const p of paths) {{
        const style = pathStyle[p.kind] || pathStyle.track;
        const coords = p.coords;
        const layer =
          coords.length >= 2
            ? L.polyline(coords, {{
                ...style,
                renderer: vectorRenderer,
                smoothFactor: 1.35,
              }}).addTo(window._gpxContentGroup)
            : L.circleMarker(coords[0], {{
                renderer: vectorRenderer,
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
        layer.bindTooltip(tip, {{ sticky: false }});
      }}
      window._gpxContentGroup.addTo(map);
      const fitBounds = payload.fitBounds;
      const initialView = payload.initialView;
      if (fitBounds) {{
        map.fitBounds(fitBounds, {{ animate: false }});
      }} else if (initialView) {{
        map.setView([initialView[0], initialView[1]], initialView[2]);
      }}
      refreshGpxPoiMarkers();
    }};

    let userLocMarker = null;
    window.gpxLinkSetUserLocation = function (lat, lng, label) {{
      if (userLocMarker) {{
        map.removeLayer(userLocMarker);
      }}
      const tip = label ? escHtml(label) : 'Current location';
      userLocMarker = L.circleMarker([lat, lng], {{
        renderer: vectorRenderer,
        radius: 11,
        color: '#1d4ed8',
        weight: 3,
        fillColor: '#93c5fd',
        fillOpacity: 0.95,
      }}).addTo(map);
      userLocMarker.bindTooltip(tip, {{ sticky: false }});
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
    marker_labels: str = "auto",
) -> str:
    """Standalone HTML with Leaflet OSM, waypoint markers, and track/route paths."""
    payload = build_map_js_payload(
        waypoints,
        paths,
        fit_padded_bounds=fit_padded_bounds,
        map_center_and_zoom=map_center_and_zoom,
        marker_labels=marker_labels,
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
