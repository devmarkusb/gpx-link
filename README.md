# gpx-link

View **GPX waypoints** on an **OpenStreetMap** basemap (Leaflet), **zoomed to fit** all points from one or more files. Click a marker to open the same location in **Google Maps** in the browser (exact coordinates so the pin matches the GPX data; you can explore nearby places there or start navigation).

The project keeps a **small, tested core library** and **CLI** separate from the **optional Qt desktop GUI**.

## Requirements

- Python **3.10+**
- CLI and library: [`gpxpy`](https://github.com/tkrajina/gpxpy)
- GUI (optional): [PySide6](https://wiki.qt.io/Qt_for_Python) with **Qt WebEngine** (for the embedded map)

## Install

```bash
# Library + CLI only
pip install gpx-link
# or, from a clone:
uv sync
```

Desktop app (Linux, macOS, Windows):

```bash
pip install 'gpx-link[gui]'
# or:
uv sync --extra gui
```

## Command line

```bash
# One JSON object per waypoint (source path, name, lat/lon, elevation, description)
gpx-link list track1.gpx track2.gpx

# Standalone HTML map on stdout (save and open in a browser)
gpx-link html tour.gpx > map.html

gpx-link html tour.gpx -o map.html
```

Pipe or save the `html` output and open it locally; it loads Leaflet and OSM tiles from the network.

## Desktop GUI

```bash
gpx-link-gui
gpx-link-gui ./routes/*.gpx
```

Use **Open GPX…** to load or replace files. The window embeds the same map HTML as the CLI generator.

If the GUI command is missing or fails, install the `gui` extra and ensure Qt WebEngine is available on your platform.

## Behavior and URLs

- **Map**: OpenStreetMap tiles via Leaflet (`html_map.build_leaflet_html`).
- **Fit**: Bounds are computed from all waypoints, then padded so the view is not flush against the edges.
- **Google Maps**: Each waypoint gets a coordinate-based Maps URL (`maps_urls.google_maps_url`). That opens the place at the GPX position; using the waypoint name alone for search would often pick the wrong POI, so the link is **lat/lng–first**.

## Project layout

| Layer | Role |
| ----- | ---- |
| `gpx_link` | Parsing (`parser`), models (`models`), bounds (`bounds`), map HTML (`html_map`), Google Maps URLs (`maps_urls`) |
| `gpx_link.cli` | `gpx-link` — `list` and `html` subcommands |
| `gpx_link.gui.app` | `gpx-link-gui` — Qt window + `QWebEngineView` |

## Development

```bash
uv sync
uv run pytest
uv run ruff check
uv run ruff format --check
```

## Release tooling

Version bumps and changelogs can be managed with **python-semantic-release** (see `pyproject.toml` and `CHANGELOG.md`). Publishing to PyPI expects your own Trusted Publisher and GitHub environment setup.

## License

See [LICENSE](LICENSE).
