from __future__ import annotations

import json
from pathlib import Path

import pytest

from gpx_link.cli import main
from tests.conftest import SAMPLE_GPX


def test_cli_list_prints_json_lines(
    capsys: pytest.CaptureFixture[str],
    sample_gpx_path: Path,
) -> None:
    code = main(["list", str(sample_gpx_path)])
    assert code == 0
    out = capsys.readouterr().out.strip().splitlines()
    assert len(out) == 2
    row0 = json.loads(out[0])
    assert row0["name"] == "Alpha"
    assert row0["latitude"] == 45.5


def test_cli_html_writes_stdout(
    capsys: pytest.CaptureFixture[str],
    sample_gpx_path: Path,
) -> None:
    code = main(["html", str(sample_gpx_path)])
    assert code == 0
    html = capsys.readouterr().out
    assert "<!DOCTYPE html>" in html
    assert "OpenStreetMap" in html or "openstreetmap" in html


def test_cli_html_output_file(tmp_path: Path) -> None:
    gpx = tmp_path / "t.gpx"
    gpx.write_text(SAMPLE_GPX, encoding="utf-8")
    out = tmp_path / "m.html"
    code = main(["html", str(gpx), "-o", str(out)])
    assert code == 0
    text = out.read_text(encoding="utf-8")
    assert "L.divIcon" in text
    assert "L.circleMarker" in text


def test_cli_list_missing_file() -> None:
    with pytest.raises(FileNotFoundError):
        main(["list", "/nonexistent/path/file.gpx"])
