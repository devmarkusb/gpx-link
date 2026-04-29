from __future__ import annotations

from pathlib import Path

import pytest

from gpx_link.parser import load_waypoints_from_files, load_waypoints_from_paths
from tests.conftest import EMPTY_TRACK_GPX, SAMPLE_GPX


def test_load_waypoints_parses_names_and_coords(tmp_path: Path) -> None:
    p = tmp_path / "t.gpx"
    p.write_text(SAMPLE_GPX, encoding="utf-8")
    wpts = load_waypoints_from_paths([p])
    assert len(wpts) == 2
    assert wpts[0].name == "Alpha"
    assert wpts[0].latitude == 45.5
    assert wpts[0].longitude == -122.5
    assert wpts[0].elevation_m == 100.0
    assert wpts[0].description == "First"
    assert wpts[0].source_path == p.resolve()
    assert wpts[1].name == "Beta"
    assert wpts[1].elevation_m is None


def test_load_empty_waypoints(tmp_path: Path) -> None:
    p = tmp_path / "empty.gpx"
    p.write_text(EMPTY_TRACK_GPX, encoding="utf-8")
    assert load_waypoints_from_paths([p]) == []


def test_load_missing_file(tmp_path: Path) -> None:
    missing = tmp_path / "nope.gpx"
    with pytest.raises(FileNotFoundError, match="Not a file"):
        load_waypoints_from_paths([missing])


def test_load_waypoints_from_files_str_paths(tmp_path: Path) -> None:
    p = tmp_path / "t.gpx"
    p.write_text(SAMPLE_GPX, encoding="utf-8")
    wpts = load_waypoints_from_files([str(p)])
    assert len(wpts) == 2
