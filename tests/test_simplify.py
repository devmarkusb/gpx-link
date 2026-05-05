from __future__ import annotations

from gpx_link.simplify import simplify_polyline_points


def test_simplify_keeps_short_polyline() -> None:
    pts = ((0.0, 0.0), (1.0, 1.0))
    assert simplify_polyline_points(pts, max_points=10) == pts


def test_simplify_reduces_zigzag() -> None:
    pts = tuple((i * 1e-5, 0.0) for i in range(200))
    mid = (100 * 1e-5, 0.05)
    pts2 = pts[:100] + (mid,) + pts[100:]
    out = simplify_polyline_points(tuple(pts2), epsilon_m=30.0, max_points=8000)
    assert len(out) < len(pts2)
    assert out[0] == pts2[0]
    assert out[-1] == pts2[-1]


def test_simplify_respects_max_points() -> None:
    pts = tuple((i * 1e-4, 0.0) for i in range(5000))
    out = simplify_polyline_points(pts, epsilon_m=1.0, max_points=120)
    assert len(out) <= 120
