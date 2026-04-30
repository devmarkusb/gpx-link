from __future__ import annotations

from urllib.parse import unquote

from gpx_link.maps_urls import google_maps_url


def test_google_maps_url_encodes_coordinates_when_no_place_name() -> None:
    url = google_maps_url(45.5, -122.5)
    assert url.startswith("https://www.google.com/maps/search/?api=1&query=")
    q = unquote(url.split("query=", 1)[1])
    assert q == "45.5000000,-122.5000000"


def test_google_maps_url_named_waypoint_search_centered_on_coordinates() -> None:
    url = google_maps_url(48.8584, 2.2945, name="Eiffel Tower")
    assert url.startswith("https://www.google.com/maps/search/")
    assert "/@48.8584000,2.2945000,17z" in url
    assert "Eiffel%20Tower" in url


def test_google_maps_url_generic_or_blank_name_falls_back_to_coordinates() -> None:
    for label in ("Waypoint", "waypoint", "  Waypoint  ", "", None):
        url = google_maps_url(1.0, 2.0, name=label)
        assert "?api=1&query=" in url
        q = unquote(url.split("query=", 1)[1])
        assert q == "1.0000000,2.0000000"
