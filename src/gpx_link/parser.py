from __future__ import annotations

from pathlib import Path

import gpxpy

from gpx_link.models import Waypoint


def load_waypoints_from_paths(paths: list[Path]) -> list[Waypoint]:
    """Parse GPX files and return all waypoints in path order."""
    out: list[Waypoint] = []
    for path in paths:
        out.extend(_load_one(path))
    return out


def load_waypoints_from_files(files: list[str]) -> list[Waypoint]:
    """Parse GPX files given as strings (paths)."""
    return load_waypoints_from_paths([Path(f).expanduser().resolve() for f in files])


def _load_one(path: Path) -> list[Waypoint]:
    if not path.is_file():
        msg = f"Not a file: {path}"
        raise FileNotFoundError(msg)
    raw = path.read_bytes()
    text = raw.decode("utf-8", errors="replace")
    gpx = gpxpy.parse(text)
    result: list[Waypoint] = []
    for w in gpx.waypoints:
        name = (w.name or "").strip() or "Waypoint"
        result.append(
            Waypoint(
                source_path=path.resolve(),
                name=name,
                latitude=float(w.latitude),
                longitude=float(w.longitude),
                elevation_m=float(w.elevation) if w.elevation is not None else None,
                description=(w.description or "").strip() or None,
            )
        )
    return result
