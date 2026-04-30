from __future__ import annotations

import sys
from pathlib import Path

from gpx_link.html_map import build_leaflet_html
from gpx_link.parser import load_map_features_from_paths


def main(argv: list[str] | None = None) -> int:
    path_args = list(sys.argv[1:] if argv is None else argv)
    paths = [Path(a).expanduser().resolve() for a in path_args]

    try:
        from PySide6.QtCore import QUrl
        from PySide6.QtWebEngineWidgets import QWebEngineView
        from PySide6.QtWidgets import (
            QApplication,
            QFileDialog,
            QMainWindow,
            QMessageBox,
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

    class MainWindow(QMainWindow):
        def __init__(self, initial_paths: list[Path]) -> None:
            super().__init__()
            self.setWindowTitle("GPX Link")
            self._paths: list[Path] = list(initial_paths)
            self._web = QWebEngineView()
            central = QWidget()
            layout = QVBoxLayout(central)
            layout.setContentsMargins(0, 0, 0, 0)
            layout.addWidget(self._web)
            self.setCentralWidget(central)

            tb = QToolBar()
            tb.addAction("Open GPX…", self._open_files)
            self.addToolBar(tb)

            self._reload()

        def _open_files(self) -> None:
            files, _ = QFileDialog.getOpenFileNames(
                self,
                "Open GPX files",
                "",
                "GPX files (*.gpx *.GPX);;All files (*)",
            )
            if files:
                self._paths = [Path(f).expanduser().resolve() for f in files]
                self._reload()

        def _reload(self) -> None:
            if not self._paths:
                html = build_leaflet_html([])
                self._web.setHtml(html, QUrl("https://cdn.jsdelivr.net/"))
                return
            try:
                wpts, geopaths = load_map_features_from_paths(self._paths)
            except OSError as e:
                QMessageBox.warning(self, "GPX", str(e))
                return
            if not wpts and not geopaths:
                msg = (
                    "No waypoints, track points, or route points found "
                    "in the selected file(s)."
                )
                QMessageBox.information(self, "GPX", msg)
            html = build_leaflet_html(wpts, geopaths)
            self._web.setHtml(html, QUrl("https://cdn.jsdelivr.net/"))

    # Do not pass GPX paths as Qt arguments; only the real process argv.
    app = QApplication(sys.argv)
    win = MainWindow(paths)
    win.resize(1000, 700)
    win.show()
    return app.exec()


if __name__ == "__main__":
    raise SystemExit(main())
