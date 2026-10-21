from __future__ import annotations

import pytest

from gpx_link.html_map import build_leaflet_html
from gpx_link.map_view import parse_map_view


@pytest.mark.parametrize(
    "raw",
    [
        None,
        "",
        "null",
        "{}",
        "[]",
        "[1,2]",
        "[1,2,null]",
        [1, 2, 10**400],
        [float("nan"), 2, 3],
        [1, float("inf"), 3],
        "[1,2,1e309]",
    ],
)
def test_invalid_view_is_ignored(raw: object) -> None:
    assert parse_map_view(raw) is None


def test_wrapped_longitude_and_fractional_zoom_are_preserved() -> None:
    assert parse_map_view("[45, 540, 12.4]") == (45.0, 540.0, 12)
    assert parse_map_view((45, 540, 12)) == (45.0, 540.0, 12)


def test_invalid_initial_view_does_not_break_map_document() -> None:
    html = build_leaflet_html(
        [],
        fit_padded_bounds=None,
        map_center_and_zoom=(float("nan"), 2, 3),
    )
    assert "center: [20.0, 0.0]" in html
    assert '"initialView":null' in html
