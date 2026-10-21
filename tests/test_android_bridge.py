from __future__ import annotations

import importlib.util
import json
from pathlib import Path

import pytest

_spec = importlib.util.spec_from_file_location(
    "bridge",
    Path(__file__).resolve().parents[1] / "android/app/src/main/python/bridge.py",
)
assert _spec is not None and _spec.loader is not None
bridge = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(bridge)


@pytest.mark.parametrize(
    "view",
    ["[1, 2, 1e309]", "[NaN, 2, 3]", "[1, Infinity, 3]"],
)
def test_render_ignores_nonfinite_saved_view(view: str) -> None:
    result = json.loads(bridge.render("[]", view))
    assert result["ok"] is True
    assert "center: [20.0, 0.0]" in result["html"]


def test_render_restores_saved_view() -> None:
    result = json.loads(bridge.render("[]", "[48.85, 2.35, 11]"))
    assert result["ok"] is True
    assert "center: [48.85, 2.35]" in result["html"]


def test_render_reports_invalid_gpx(sample_gpx_path: Path) -> None:
    sample_gpx_path.write_text("<gpx>", encoding="utf-8")
    result = json.loads(bridge.render(json.dumps([str(sample_gpx_path)])))
    assert result["ok"] is False
    assert result["error"]


@pytest.mark.parametrize("view", ["[120,2,3]", "[1,2,-3]", "[1,2,20]"])
def test_invalid_saved_view_keeps_gpx_features_and_fits_them(
    sample_gpx_path: Path,
    view: str,
) -> None:
    result = json.loads(bridge.render(json.dumps([str(sample_gpx_path)]), view))
    assert result["ok"] is True
    assert '"initialView":null' in result["html"]
    assert '"fitBounds":null' not in result["html"]
    assert '"name":"Alpha"' in result["html"]
    assert '"name":"Beta"' in result["html"]
