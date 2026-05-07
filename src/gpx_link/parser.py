from __future__ import annotations

from collections.abc import MutableMapping
from pathlib import Path
from typing import Any

import gpxpy

from gpx_link.models import GeoPath, Waypoint


def load_map_features_from_paths(
    paths: list[Path],
) -> tuple[list[Waypoint], list[GeoPath]]:
    """Parse GPX files and return waypoints plus track/route paths in file order."""
    waypoints: list[Waypoint] = []
    geo_paths: list[GeoPath] = []
    for path in paths:
        w, g = _load_one(path)
        waypoints.extend(w)
        geo_paths.extend(g)
    return waypoints, geo_paths


def load_map_features_from_paths_cached(
    paths: list[Path],
    cache: MutableMapping[str, tuple[int, list[Waypoint], list[GeoPath]]],
) -> tuple[list[Waypoint], list[GeoPath]]:
    """Parse like ``load_map_features_from_paths`` with per-file mtime cache hits."""
    waypoints: list[Waypoint] = []
    geo_paths: list[GeoPath] = []
    for path in paths:
        key = str(path.resolve())
        mtime_ns = path.stat().st_mtime_ns
        hit = cache.get(key)
        if hit is not None and hit[0] == mtime_ns:
            w, g = hit[1], hit[2]
        else:
            w, g = _load_one(path)
            cache[key] = (mtime_ns, w, g)
        waypoints.extend(w)
        geo_paths.extend(g)
    return waypoints, geo_paths


def load_waypoints_from_paths(paths: list[Path]) -> list[Waypoint]:
    """Parse GPX files and return all waypoints in path order."""
    wpts, _ = load_map_features_from_paths(paths)
    return wpts


def load_waypoints_from_files(files: list[str]) -> list[Waypoint]:
    """Parse GPX files given as strings (paths)."""
    return load_waypoints_from_paths([Path(f).expanduser().resolve() for f in files])


def _coords_latlon(segment_points: list[Any]) -> tuple[tuple[float, float], ...]:
    out: list[tuple[float, float]] = []
    for p in segment_points:
        lat, lon = p.latitude, p.longitude
        if lat is None or lon is None:
            continue
        out.append((float(lat), float(lon)))
    return tuple(out)


def _load_one(path: Path) -> tuple[list[Waypoint], list[GeoPath]]:
    if not path.is_file():
        msg = f"Not a file: {path}"
        raise FileNotFoundError(msg)
    raw = path.read_bytes()
    text = raw.decode("utf-8", errors="replace")
    gpx = gpxpy.parse(text)
    resolved = path.resolve()
    result: list[Waypoint] = []
    for w in gpx.waypoints:
        name = (w.name or "").strip() or "Waypoint"
        sym = (w.symbol or "").strip() or None
        wpt_type = (w.type or "").strip() or None
        result.append(
            Waypoint(
                source_path=resolved,
                name=name,
                latitude=float(w.latitude),
                longitude=float(w.longitude),
                elevation_m=float(w.elevation) if w.elevation is not None else None,
                description=(w.description or "").strip() or None,
                symbol=sym,
                waypoint_type=wpt_type,
            )
        )
    paths: list[GeoPath] = []
    for rte in gpx.routes:
        pts = _coords_latlon(rte.points)
        if not pts:
            continue
        label = (rte.name or "").strip() or "Route"
        paths.append(
            GeoPath(
                source_path=resolved,
                name=label,
                kind="route",
                points=pts,
            )
        )
    for track in gpx.tracks:
        base = (track.name or "").strip() or "Track"
        segs = track.segments
        for i, seg in enumerate(segs):
            pts = _coords_latlon(seg.points)
            if not pts:
                continue
            if len(segs) > 1:
                label = f"{base} (segment {i + 1})"
            else:
                label = base
            paths.append(
                GeoPath(
                    source_path=resolved,
                    name=label,
                    kind="track",
                    points=pts,
                )
            )
    return result, paths
