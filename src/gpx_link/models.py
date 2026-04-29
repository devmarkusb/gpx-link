from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True, slots=True)
class Waypoint:
    """A GPX waypoint (`<wpt>`) with optional metadata."""

    source_path: Path
    name: str
    latitude: float
    longitude: float
    elevation_m: float | None = None
    description: str | None = None
