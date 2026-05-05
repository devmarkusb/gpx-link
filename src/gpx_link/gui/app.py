from __future__ import annotations

import json
import sys
from pathlib import Path

from gpx_link.bounds import bounds_for_map
from gpx_link.html_map import (
    build_leaflet_map_shell_html,
    build_map_js_payload,
    map_js_payload_literal,
)
from gpx_link.models import GeoPath, Waypoint
from gpx_link.parser import load_map_features_from_paths_cached


def main(argv: list[str] | None = None) -> int:
    path_args = list(sys.argv[1:] if argv is None else argv)
    paths = [Path(a).expanduser().resolve() for a in path_args]

    try:
        from PySide6.QtCore import QByteArray, QSettings, Qt, QUrl
        from PySide6.QtGui import QAction, QCloseEvent, QDesktopServices, QKeySequence
        from PySide6.QtWebEngineCore import QWebEngineNewWindowRequest
        from PySide6.QtWebEngineWidgets import QWebEngineView
        from PySide6.QtWidgets import (
            QAbstractItemView,
            QApplication,
            QFileDialog,
            QHBoxLayout,
            QListWidget,
            QListWidgetItem,
            QMainWindow,
            QMessageBox,
            QPushButton,
            QSplitter,
            QToolBar,
            QVBoxLayout,
            QWidget,
        )
    except ImportError:
        print(  # noqa: T201
            "gpx-link-gui requires optional dependencies. Install with:\n"
            "  uv sync --extra gui\n"
            "or: pip install 'gpx-link[gui]'",
            file=sys.stderr,
        )
        return 1

    _SETTINGS_ORG = "GPX-Link"
    _SETTINGS_APP = "GPXLink"
    _LAST_OPEN_DIR_KEY = "last_open_gpx_directory"
    _SHOW_FILE_PANEL_KEY = "show_file_panel"
    _SPLITTER_STATE_KEY = "main_splitter_state"
    _GPX_FILE_LIST_KEY = "gpx_file_list_v1"

    class MainWindow(QMainWindow):
        def __init__(self, initial_paths: list[Path]) -> None:
            super().__init__()
            self.setWindowTitle("GPX Link")
            self._settings = QSettings(_SETTINGS_ORG, _SETTINGS_APP)
            self._web = QWebEngineView()
            self._file_list = QListWidget()
            self._file_list.setMinimumWidth(220)
            self._file_list.setSelectionMode(
                QAbstractItemView.SelectionMode.ExtendedSelection
            )
            self._file_list.itemChanged.connect(self._on_file_item_changed)

            file_panel = QWidget()
            file_layout = QVBoxLayout(file_panel)
            file_layout.setContentsMargins(0, 0, 0, 0)
            file_layout.setSpacing(0)
            btn_row = QWidget()
            btn_layout = QHBoxLayout(btn_row)
            btn_layout.setContentsMargins(4, 4, 4, 2)
            sel_all = QPushButton("Select all")
            sel_all.setToolTip("Check every GPX file (show all on the map)")
            sel_all.clicked.connect(self._select_all_gpx_files)
            btn_layout.addWidget(sel_all)
            sel_none = QPushButton("Unselect all")
            sel_none.setToolTip("Uncheck every GPX file (hide all from the map)")
            sel_none.clicked.connect(self._unselect_all_gpx_files)
            btn_layout.addWidget(sel_none)
            del_btn = QPushButton("Delete")
            del_btn.setToolTip(
                "Remove selected rows from this list (click or Shift+click rows; "
                "files on disk are not deleted)"
            )
            del_btn.clicked.connect(self._delete_selected_gpx_files)
            btn_layout.addWidget(del_btn)
            file_layout.addWidget(btn_row)
            file_layout.addWidget(self._file_list, stretch=1)
            self._file_panel = file_panel

            self._splitter = QSplitter()
            self._splitter.addWidget(self._file_panel)
            self._splitter.addWidget(self._web)
            self._splitter.setStretchFactor(0, 0)
            self._splitter.setStretchFactor(1, 1)

            central = QWidget()
            layout = QVBoxLayout(central)
            layout.setContentsMargins(0, 0, 0, 0)
            layout.addWidget(self._splitter)
            self.setCentralWidget(central)
            self._web.page().newWindowRequested.connect(self._on_new_window_requested)

            tb = QToolBar()
            tb.addAction("Open GPX…", self._open_files)
            fit_act = QAction("Fit to route", self)
            fit_act.setShortcut(QKeySequence("Ctrl+Shift+F"))
            fit_act.setToolTip(
                "Zoom the map to show all data in the current selection "
                "(shortcut: Ctrl+Shift+F)"
            )
            fit_act.triggered.connect(self._fit_map_to_visible_route)
            tb.addAction(fit_act)
            self._action_file_panel = QAction("GPX file list", self)
            self._action_file_panel.setCheckable(True)
            self._action_file_panel.setShortcut(QKeySequence("Ctrl+Shift+L"))
            self._action_file_panel.setToolTip(
                "Show or hide the GPX file list (full width for the map)"
            )
            show_panel = self._settings.value(_SHOW_FILE_PANEL_KEY, True)
            if isinstance(show_panel, str):
                show_panel = show_panel.lower() in ("true", "1", "yes")
            else:
                show_panel = bool(show_panel)
            saved_state = self._settings.value(_SPLITTER_STATE_KEY)
            if (
                show_panel
                and isinstance(saved_state, QByteArray)
                and not saved_state.isEmpty()
            ):
                self._splitter.restoreState(saved_state)
            self._action_file_panel.setChecked(show_panel)
            self._action_file_panel.toggled.connect(self._on_file_panel_toggled)
            tb.addAction(self._action_file_panel)
            self.addToolBar(tb)
            self._apply_file_panel_visibility(show_panel)

            self._restore_gpx_file_list_from_settings()
            self._add_paths_to_list(initial_paths)
            self._map_shell_ready = False
            self._awaiting_shell_load = False
            self._pending_map_payload: dict[str, object] | None = None
            self._parse_cache: dict[str, tuple[int, list[Waypoint], list[GeoPath]]] = {}
            self._last_map_view: tuple[float, float, int] | None = None
            self._reload()

        def _persist_layout(self) -> None:
            self._settings.setValue(_SHOW_FILE_PANEL_KEY, self._file_panel.isVisible())
            if self._file_panel.isVisible():
                self._settings.setValue(_SPLITTER_STATE_KEY, self._splitter.saveState())
            self._persist_gpx_file_list()

        def _persist_gpx_file_list(self) -> None:
            entries: list[dict[str, object]] = []
            for i in range(self._file_list.count()):
                item = self._file_list.item(i)
                data = item.data(Qt.ItemDataRole.UserRole)
                if not isinstance(data, str):
                    continue
                entries.append(
                    {
                        "path": data,
                        "checked": item.checkState() == Qt.CheckState.Checked,
                    }
                )
            self._settings.setValue(_GPX_FILE_LIST_KEY, json.dumps(entries))

        def _restore_gpx_file_list_from_settings(self) -> None:
            raw = self._settings.value(_GPX_FILE_LIST_KEY, "")
            if not isinstance(raw, str) or not raw.strip():
                return
            try:
                data = json.loads(raw)
            except json.JSONDecodeError:
                return
            if not isinstance(data, list):
                return
            self._file_list.blockSignals(True)
            try:
                for entry in data:
                    if isinstance(entry, str):
                        path_obj = Path(entry).expanduser()
                        chk = True
                    elif isinstance(entry, dict):
                        p = entry.get("path")
                        if not isinstance(p, str):
                            continue
                        path_obj = Path(p).expanduser()
                        c = entry.get("checked", True)
                        chk = (
                            c
                            if isinstance(c, bool)
                            else str(c).lower()
                            in (
                                "true",
                                "1",
                                "yes",
                            )
                        )
                    else:
                        continue
                    if not path_obj.is_file():
                        continue
                    resolved = path_obj.resolve()
                    key = str(resolved)
                    if key in self._paths_in_list():
                        continue
                    it = QListWidgetItem(resolved.name)
                    it.setData(Qt.ItemDataRole.UserRole, key)
                    it.setFlags(
                        it.flags()
                        | Qt.ItemFlag.ItemIsUserCheckable
                        | Qt.ItemFlag.ItemIsSelectable
                        | Qt.ItemFlag.ItemIsEnabled
                    )
                    it.setCheckState(
                        Qt.CheckState.Checked if chk else Qt.CheckState.Unchecked
                    )
                    it.setToolTip(key)
                    self._file_list.addItem(it)
            finally:
                self._file_list.blockSignals(False)

        def _on_file_panel_toggled(self, visible: bool) -> None:
            if not visible:
                self._settings.setValue(_SPLITTER_STATE_KEY, self._splitter.saveState())
            self._apply_file_panel_visibility(visible)
            if visible:
                saved = self._settings.value(_SPLITTER_STATE_KEY)
                if isinstance(saved, QByteArray) and not saved.isEmpty():
                    self._splitter.restoreState(saved)
            self._settings.setValue(_SHOW_FILE_PANEL_KEY, visible)

        def _apply_file_panel_visibility(self, visible: bool) -> None:
            self._file_panel.setVisible(visible)
            if visible and self._splitter.sizes()[0] == 0:
                total = max(self._splitter.width(), 400)
                left = min(280, total // 3)
                self._splitter.setSizes([left, max(1, total - left)])

        def closeEvent(self, event: QCloseEvent) -> None:  # noqa: N802
            self._persist_layout()
            super().closeEvent(event)

        def _saved_open_directory(self) -> str:
            v = self._settings.value(_LAST_OPEN_DIR_KEY, "")
            return v if isinstance(v, str) else ""

        def _save_last_directory(self, path_or_file: Path) -> None:
            p = path_or_file.expanduser().resolve()
            self._settings.setValue(
                _LAST_OPEN_DIR_KEY, str(p.parent if p.is_file() else p)
            )

        def _paths_in_list(self) -> set[str]:
            seen: set[str] = set()
            for i in range(self._file_list.count()):
                item = self._file_list.item(i)
                data = item.data(Qt.ItemDataRole.UserRole)
                if isinstance(data, str):
                    seen.add(data)
            return seen

        def _prune_parse_cache(self) -> None:
            keep = self._paths_in_list()
            for key in list(self._parse_cache):
                if key not in keep:
                    del self._parse_cache[key]

        def _store_map_view(self, result: object) -> None:
            if isinstance(result, list) and len(result) >= 3:
                try:
                    self._last_map_view = (
                        float(result[0]),
                        float(result[1]),
                        int(round(float(result[2]))),
                    )
                except (TypeError, ValueError):
                    pass

        def _apply_map_js_payload(self, payload: dict[str, object]) -> None:
            lit = map_js_payload_literal(payload)
            js = (
                "(function(){ gpxLinkApplyPayload("
                + lit
                + "); var c = map.getCenter(); "
                "return [c.lat, c.lng, map.getZoom()]; })()"
            )
            self._web.page().runJavaScript(js, self._store_map_view)

        def _on_map_shell_load_finished(self, ok: bool) -> None:
            try:
                self._web.loadFinished.disconnect(self._on_map_shell_load_finished)
            except TypeError:
                pass
            self._awaiting_shell_load = False
            if ok:
                self._map_shell_ready = True
                if self._pending_map_payload is not None:
                    p = self._pending_map_payload
                    self._pending_map_payload = None
                    self._apply_map_js_payload(p)
            else:
                self._map_shell_ready = False

        def _add_paths_to_list(self, paths: list[Path]) -> None:
            self._file_list.blockSignals(True)
            try:
                existing = self._paths_in_list()
                for p in paths:
                    resolved = p.expanduser().resolve()
                    key = str(resolved)
                    if key in existing:
                        continue
                    existing.add(key)
                    it = QListWidgetItem(resolved.name)
                    it.setData(Qt.ItemDataRole.UserRole, key)
                    it.setFlags(
                        it.flags()
                        | Qt.ItemFlag.ItemIsUserCheckable
                        | Qt.ItemFlag.ItemIsSelectable
                        | Qt.ItemFlag.ItemIsEnabled
                    )
                    it.setCheckState(Qt.CheckState.Checked)
                    it.setToolTip(key)
                    self._file_list.addItem(it)
            finally:
                self._file_list.blockSignals(False)
            self._persist_gpx_file_list()

        def _checked_paths(self) -> list[Path]:
            out: list[Path] = []
            for i in range(self._file_list.count()):
                item = self._file_list.item(i)
                if item.checkState() != Qt.CheckState.Checked:
                    continue
                data = item.data(Qt.ItemDataRole.UserRole)
                if isinstance(data, str):
                    out.append(Path(data))
            return out

        def _on_file_item_changed(self, _item: QListWidgetItem) -> None:
            self._persist_gpx_file_list()
            self._reload()

        def _set_all_file_check_states(self, state: Qt.CheckState) -> None:
            self._file_list.blockSignals(True)
            try:
                for i in range(self._file_list.count()):
                    self._file_list.item(i).setCheckState(state)
            finally:
                self._file_list.blockSignals(False)
            self._persist_gpx_file_list()
            self._reload()

        def _select_all_gpx_files(self) -> None:
            self._set_all_file_check_states(Qt.CheckState.Checked)

        def _unselect_all_gpx_files(self) -> None:
            self._set_all_file_check_states(Qt.CheckState.Unchecked)

        def _delete_selected_gpx_files(self) -> None:
            selected = self._file_list.selectedItems()
            if not selected:
                QMessageBox.information(
                    self,
                    "GPX Link",
                    "Select one or more rows in the list (click or Shift+click), "
                    "then click Delete.",
                )
                return
            rows = sorted(
                {self._file_list.row(i) for i in selected},
                reverse=True,
            )
            self._file_list.blockSignals(True)
            try:
                for r in rows:
                    if r >= 0:
                        self._file_list.takeItem(r)
            finally:
                self._file_list.blockSignals(False)
            self._prune_parse_cache()
            self._persist_gpx_file_list()
            self._reload()

        def _open_files(self) -> None:
            files, _ = QFileDialog.getOpenFileNames(
                self,
                "Open GPX files",
                self._saved_open_directory(),
                "GPX files (*.gpx *.GPX);;All files (*)",
            )
            if files:
                resolved = [Path(f).expanduser().resolve() for f in files]
                self._add_paths_to_list(resolved)
                self._save_last_directory(resolved[0])
                self._reload()

        def _on_new_window_requested(self, request: QWebEngineNewWindowRequest) -> None:
            # Qt WebEngine does not spawn a real browser window for window.open();
            # open http(s) links in the system default browser (e.g. Google Maps).
            url = request.requestedUrl()
            if url.isValid() and url.scheme() in ("http", "https"):
                QDesktopServices.openUrl(url)

        def _fit_map_to_visible_route(self) -> None:
            if not self._map_shell_ready:
                return
            paths = self._checked_paths()
            if not paths:
                QMessageBox.information(
                    self,
                    "GPX Link",
                    "Select at least one GPX file to fit the map.",
                )
                return
            try:
                wpts, geopaths = load_map_features_from_paths_cached(
                    paths, self._parse_cache
                )
            except OSError as e:
                QMessageBox.warning(self, "GPX", str(e))
                return
            b = bounds_for_map(wpts, geopaths)
            if b is None:
                QMessageBox.information(
                    self,
                    "GPX Link",
                    "No coordinates in the current selection to fit the map.",
                )
                return
            padded = b.padded()
            corners: list[list[float]] = [
                [padded.min_lat, padded.min_lon],
                [padded.max_lat, padded.max_lon],
            ]
            lit = json.dumps(corners, separators=(",", ":")).replace("<", "\\u003c")
            js = (
                "(function(){ map.fitBounds(" + lit + "); var c = map.getCenter(); "
                "return [c.lat, c.lng, map.getZoom()]; })()"
            )
            self._web.page().runJavaScript(js, self._store_map_view)

        def _reload(self) -> None:
            self._prune_parse_cache()
            paths = self._checked_paths()
            if not paths:
                payload = build_map_js_payload(
                    [],
                    [],
                    fit_padded_bounds=None,
                    map_center_and_zoom=self._last_map_view,
                )
            else:
                try:
                    wpts, geopaths = load_map_features_from_paths_cached(
                        paths, self._parse_cache
                    )
                except OSError as e:
                    QMessageBox.warning(self, "GPX", str(e))
                    return
                if not wpts and not geopaths:
                    msg = (
                        "No waypoints, track points, or route points found "
                        "in the selected file(s)."
                    )
                    QMessageBox.information(self, "GPX", msg)
                payload = build_map_js_payload(
                    wpts,
                    geopaths,
                    fit_padded_bounds=None,
                    map_center_and_zoom=self._last_map_view,
                )
                self._save_last_directory(paths[0])

            if not self._map_shell_ready:
                self._pending_map_payload = payload
                if not self._awaiting_shell_load:
                    self._awaiting_shell_load = True
                    self._web.loadFinished.connect(self._on_map_shell_load_finished)
                    self._web.setHtml(
                        build_leaflet_map_shell_html(),
                        QUrl("https://cdn.jsdelivr.net/"),
                    )
                return

            self._apply_map_js_payload(payload)

    # Do not pass GPX paths as Qt arguments; only the real process argv.
    app = QApplication(sys.argv)
    win = MainWindow(paths)
    win.resize(1000, 700)
    win.show()
    return app.exec()


if __name__ == "__main__":
    raise SystemExit(main())
