from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Literal


@dataclass(frozen=True, slots=True)
class Waypoint:
    """A GPX waypoint (`<wpt>`) with optional metadata."""

    source_path: Path
    name: str
    latitude: float
    longitude: float
    elevation_m: float | None = None
    description: str | None = None
    #: GPX ``<sym>`` (Garmin-style symbol name, when present).
    symbol: str | None = None
    #: GPX ``<type>`` — POI category / label from the file.
    waypoint_type: str | None = None


@dataclass(frozen=True, slots=True)
class GeoPath:
    """Path from GPX `<trkpt>` or `<rtept>` chains (one segment or route)."""

    source_path: Path
    name: str
    kind: Literal["track", "route"]
    points: tuple[tuple[float, float], ...]
