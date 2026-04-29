from __future__ import annotations

from urllib.parse import unquote

from gpx_link.maps_urls import google_maps_url


def test_google_maps_url_encodes_coordinates() -> None:
    url = google_maps_url(45.5, -122.5)
    assert url.startswith("https://www.google.com/maps/search/?api=1&query=")
    q = unquote(url.split("query=", 1)[1])
    assert q == "45.5000000,-122.5000000"
