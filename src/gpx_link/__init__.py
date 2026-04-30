"""GPX waypoint parsing, map HTML generation, and Google Maps URLs."""

from gpx_link.bounds import Bounds, bounds_for_waypoints
from gpx_link.html_map import build_leaflet_html
from gpx_link.maps_urls import google_maps_url
from gpx_link.models import GeoPath, Waypoint
from gpx_link.parser import (
    load_map_features_from_paths,
    load_waypoints_from_files,
    load_waypoints_from_paths,
)

__all__ = [
    "Bounds",
    "GeoPath",
    "Waypoint",
    "bounds_for_waypoints",
    "build_leaflet_html",
    "google_maps_url",
    "load_map_features_from_paths",
    "load_waypoints_from_files",
    "load_waypoints_from_paths",
]
