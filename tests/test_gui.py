from __future__ import annotations

import json
from pathlib import Path

import pytest

from gpx_link.map_view import parse_map_view
from gpx_link.models import Waypoint
from tests.conftest import SAMPLE_GPX


def test_gui_parse_errors_and_stale_updates(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
) -> None:
    monkeypatch.setenv("QT_QPA_PLATFORM", "offscreen")
    monkeypatch.setenv("QTWEBENGINE_CHROMIUM_FLAGS", "--disable-gpu --no-sandbox")
    core = pytest.importorskip("PySide6.QtCore")
    widgets = pytest.importorskip("PySide6.QtWidgets")
    web = pytest.importorskip("PySide6.QtWebEngineWidgets")
    web_core = pytest.importorskip("PySide6.QtWebEngineCore")
    import shiboken6

    from gpx_link.gui import app as gui

    settings = core.QSettings(str(tmp_path / "settings.ini"), core.QSettings.IniFormat)
    run_real_javascript = web_core.QWebEnginePage.runJavaScript
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
            map_calls = []
            map_scripts = []

            def record_map_call(_page, script, callback):
                map_scripts.append(script)
                if "gpxLinkApplyPayload(" not in script:
                    return
                literal = script.split("gpxLinkApplyPayload(", 1)[1].split(
                    "); var c", 1
                )[0]
                map_calls.append((json.loads(literal), callback))

            monkeypatch.setattr(
                web_core.QWebEnginePage, "runJavaScript", record_map_call
            )
            window._on_map_shell_load_finished(True)
            world = [20.0, 0.0, 2]
            map_calls[-1][1](world)
            imported = tmp_path / "imported.gpx"
            imported.write_text(SAMPLE_GPX, encoding="utf-8")
            monkeypatch.setattr(
                window,
                "_exec_file_dialog",
                lambda *_args, **_kwargs: [str(imported)],
            )
            window._open_files()
            assert map_calls[-1][0]["fitBounds"] is not None
            assert map_calls[-1][0]["initialView"] is None

            # Changing labels before the first fit completes must keep that fit.
            window._refresh_map_marker_labels()
            assert map_calls[-1][0]["fitBounds"] is not None
            fitted_view = [45.75, -122.75, 10]
            map_calls[-1][1](fitted_view)
            window._unselect_all_gpx_files()
            assert map_calls[-1][0]["fitBounds"] is None
            assert map_calls[-1][0]["initialView"] == fitted_view
            map_calls[-1][1](fitted_view)
            window._select_all_gpx_files()
            assert map_calls[-1][0]["fitBounds"] is None
            assert map_calls[-1][0]["initialView"] == fitted_view
            map_calls[-1][1](fitted_view)

            project = tmp_path / "viewport.gpxlink.json"
            monkeypatch.setattr(window, "_exec_file_dialog", lambda *_: [str(project)])
            for saved_view in ([120, 2, 3], [1, 2, -3], [1, 2, 20], [48.85, 540, 11]):
                project.write_text(
                    json.dumps(
                        {
                            "format": "gpx-link-project",
                            "version": 1,
                            "files": [{"path": str(imported), "checked": True}],
                            "map_view": saved_view,
                        }
                    ),
                    encoding="utf-8",
                )
                window._load_project()
                payload, callback = map_calls[-1]
                assert window._checked_paths() == [imported]
                assert len(payload["markers"]) == 2
                if saved_view == [48.85, 540, 11]:
                    assert payload["fitBounds"] is None
                    assert payload["initialView"] == saved_view
                    callback(saved_view)
                else:
                    assert payload["initialView"] is None
                    assert payload["fitBounds"] is not None
                    callback(fitted_view)

            window._new_project()
            map_calls[-1][1](world)
            monkeypatch.setattr(
                window,
                "_exec_file_dialog",
                lambda *_args, **_kwargs: [str(imported)],
            )
            window._open_files()
            assert map_calls[-1][0]["fitBounds"] is not None
            map_calls[-1][1](fitted_view)
            window._fit_map_to_visible_route()

            # Exercise the actual Qt/JavaScript boundary. Some PySide6 versions
            # return an empty string for JavaScript array results.
            page = web_core.QWebEnginePage(window)
            timer = core.QTimer()
            timer.setSingleShot(True)
            loop = core.QEventLoop()
            timer.timeout.connect(loop.quit)
            loaded = []
            results = []

            def page_loaded(ok):
                loaded.append(ok)
                loop.quit()

            def script_done(value):
                results.append(value)
                loop.quit()

            try:
                page.loadFinished.connect(page_loaded)
                page.setHtml("""<script>
                    const map = {
                        getCenter: () => ({lat:45.75,lng:-122.75}),
                        getZoom: () => 10,
                        fitBounds: () => {}
                    };
                    function gpxLinkApplyPayload(payload) {}
                </script>""")
                timer.start(5000)
                loop.exec()
                timer.stop()
                assert loaded == [True]
                # Both applying features and the explicit Fit action persist views.
                for script in map_scripts[-2:]:
                    results.clear()
                    run_real_javascript(page, script, script_done)
                    timer.start(5000)
                    loop.exec()
                    timer.stop()
                    assert len(results) == 1
                    assert parse_map_view(results[0]) == tuple(fitted_view)
            finally:
                timer.stop()
                shiboken6.delete(page)

            window._clear_gpx_file_list()
            window._map_shell_ready = False
            window._reload()

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
