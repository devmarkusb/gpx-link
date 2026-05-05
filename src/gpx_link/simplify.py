"""Polyline simplification for map display (tracks/routes with huge point counts)."""

from __future__ import annotations

import math
from collections.abc import Sequence


def _hav_approx_scale(lat_deg: float) -> tuple[float, float]:
    """Meters per degree longitude (scaled by cos lat) and per degree latitude."""
    lat_rad = math.radians(lat_deg)
    m_per_deg_lat = 111_320.0
    m_per_deg_lon = 111_320.0 * math.cos(lat_rad)
    if m_per_deg_lon < 10_000.0:
        m_per_deg_lon = 10_000.0
    return m_per_deg_lon, m_per_deg_lat


def _to_xy_meters(points: Sequence[tuple[float, float]]) -> list[tuple[float, float]]:
    avg_lat = sum(lat for lat, _ in points) / len(points)
    sx, sy = _hav_approx_scale(avg_lat)
    return [(lon * sx, lat * sy) for lat, lon in points]


def _dist_point_segment(
    px: float,
    py: float,
    ax: float,
    ay: float,
    bx: float,
    by: float,
) -> float:
    vx, vy = bx - ax, by - ay
    wx, wy = px - ax, py - ay
    seg2 = vx * vx + vy * vy
    if seg2 == 0.0:
        return math.hypot(wx, wy)
    t = max(0.0, min(1.0, (wx * vx + wy * vy) / seg2))
    qx, qy = ax + t * vx, ay + t * vy
    return math.hypot(px - qx, py - qy)


def _thin_consecutive_min_dist(
    points: tuple[tuple[float, float], ...],
    *,
    min_dist_m: float,
) -> tuple[tuple[float, float], ...]:
    if len(points) <= 2:
        return points
    xy = _to_xy_meters(points)
    eps = min_dist_m * min_dist_m
    out_idx = [0]
    last_xy = xy[0]
    for i in range(1, len(points) - 1):
        x, y = xy[i]
        dx, dy = x - last_xy[0], y - last_xy[1]
        if dx * dx + dy * dy >= eps:
            out_idx.append(i)
            last_xy = (x, y)
    out_idx.append(len(points) - 1)
    return tuple(points[i] for i in out_idx)


def _rdp_indices(xy: list[tuple[float, float]], epsilon_m: float) -> list[int]:
    n = len(xy)
    if n <= 2:
        return list(range(n))
    eps = max(epsilon_m, 1e-6)
    keep = {0, n - 1}
    stack = [(0, n - 1)]
    while stack:
        i0, i1 = stack.pop()
        ax, ay = xy[i0]
        bx, by = xy[i1]
        best_idx = -1
        best_d = eps
        for k in range(i0 + 1, i1):
            d = _dist_point_segment(xy[k][0], xy[k][1], ax, ay, bx, by)
            if d > best_d:
                best_d = d
                best_idx = k
        if best_idx >= 0:
            keep.add(best_idx)
            stack.append((i0, best_idx))
            stack.append((best_idx, i1))
    return sorted(keep)


def _uniform_stride(
    points: tuple[tuple[float, float], ...],
    max_points: int,
) -> tuple[tuple[float, float], ...]:
    n = len(points)
    if n <= max_points:
        return points
    if max_points < 2:
        return (points[0],)
    step = (n - 1) / (max_points - 1)
    idx = [min(n - 1, int(round(step * j))) for j in range(max_points)]
    uniq: list[int] = []
    prev = -1
    for i in idx:
        if i != prev:
            uniq.append(i)
            prev = i
    if uniq[-1] != n - 1:
        uniq.append(n - 1)
    return tuple(points[i] for i in uniq)


def simplify_polyline_points(
    points: tuple[tuple[float, float], ...],
    *,
    epsilon_m: float = 12.0,
    max_points: int = 7000,
    pre_thin_m: float = 2.5,
    rdp_input_cap: int = 25_000,
) -> tuple[tuple[float, float], ...]:
    """Reduce vertices for map drawing while keeping bounds roughly faithful.

    Applies light distance thinning, Ramer–Douglas–Peucker in local meter space,
    then caps length with uniform sampling if still above ``max_points``.
    """
    if len(points) <= 2:
        return points
    if len(points) > rdp_input_cap:
        points = _uniform_stride(points, rdp_input_cap)
    cleaned = _thin_consecutive_min_dist(points, min_dist_m=pre_thin_m)
    if len(cleaned) <= 2:
        return cleaned
    xy = _to_xy_meters(cleaned)
    idxs = _rdp_indices(list(xy), epsilon_m)
    out = tuple(cleaned[i] for i in idxs)
    return _uniform_stride(out, max_points)
