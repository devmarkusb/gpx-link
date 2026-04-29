"""GPX waypoint parsing, map HTML generation, and Google Maps URLs."""

from gpx_link.bounds import Bounds, bounds_for_waypoints
from gpx_link.html_map import build_leaflet_html
from gpx_link.maps_urls import google_maps_url
from gpx_link.models import Waypoint
from gpx_link.parser import load_waypoints_from_files, load_waypoints_from_paths

__all__ = [
    "Bounds",
    "Waypoint",
    "bounds_for_waypoints",
    "build_leaflet_html",
    "google_maps_url",
    "load_waypoints_from_files",
    "load_waypoints_from_paths",
]
