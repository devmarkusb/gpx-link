from __future__ import annotations

import json
from pathlib import Path

import pytest

from gpx_link.models import Waypoint


def test_gui_parse_errors_and_stale_updates(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
) -> None:
    monkeypatch.setenv("QT_QPA_PLATFORM", "offscreen")
    monkeypatch.setenv("QTWEBENGINE_CHROMIUM_FLAGS", "--disable-gpu --no-sandbox")
    core = pytest.importorskip("PySide6.QtCore")
    widgets = pytest.importorskip("PySide6.QtWidgets")
    web = pytest.importorskip("PySide6.QtWebEngineWidgets")
    import shiboken6

    from gpx_link.gui import app as gui

    settings = core.QSettings(str(tmp_path / "settings.ini"), core.QSettings.IniFormat)
    monkeypatch.setattr(core, "QSettings", lambda *_: settings)
    monkeypatch.setattr(web.QWebEngineView, "setHtml", lambda *_: None)
    warnings: list[str] = []
    monkeypatch.setattr(
        widgets.QMessageBox,
        "warning",
        lambda _parent, _title, text: warnings.append(text),
    )
    monkeypatch.setattr(widgets.QMessageBox, "information", lambda *_: None)
    monkeypatch.setattr(
        widgets.QMessageBox,
        "question",
        lambda *_: widgets.QMessageBox.Yes,
    )

    def run_scenarios(application) -> int:
        window = next(
            w
            for w in application.topLevelWidgets()
            if isinstance(w, widgets.QMainWindow)
        )
        try:
            bad_gpx = tmp_path / "bad.gpx"
            bad_gpx.write_text("<gpx>", encoding="utf-8")
            window._add_paths_to_list([bad_gpx])
            window._reload()
            assert warnings and "XML" in warnings[-1]
            window._map_shell_ready = True
            window._fit_map_to_visible_route()
            assert len(warnings) == 2
            window._map_shell_ready = False

            bad_project = tmp_path / "bad.gpxlink.json"
            bad_project.write_bytes(b"\xff")
            monkeypatch.setattr(
                window, "_exec_file_dialog", lambda *_: [str(bad_project)]
            )
            window._load_project()
            assert len(warnings) == 3

            bad_project.write_text(
                json.dumps(
                    {
                        "format": "gpx-link-project",
                        "version": 1,
                        "files": [],
                        "splitter_state_b64": "invalid 🗺️ layout",
                    }
                ),
                encoding="utf-8",
            )
            window._load_project()
            assert window._checked_paths() == []

            window._clear_gpx_file_list()
            valid_gpx = tmp_path / "valid.gpx"
            valid_gpx.touch()
            window._add_paths_to_list([valid_gpx])

            def reentrant_parse(_paths, _cache, *, progress):
                # A progress callback processes Qt events. New can run before this
                # older parse completes and returns its now-obsolete features.
                window._clear_gpx_file_list()
                window._reload()
                return [Waypoint(valid_gpx, "Old", 1, 2)], []

            monkeypatch.setattr(
                gui, "load_map_features_from_paths_cached", reentrant_parse
            )
            window._reload()
            assert window._checked_paths() == []
            assert window._last_wpts == []
            assert window._pending_map_payload["markers"] == []
        finally:
            window.close()
            shiboken6.delete(window)
        return 0

    monkeypatch.setattr(widgets.QApplication, "exec", run_scenarios)
    assert gui.main([]) == 0
