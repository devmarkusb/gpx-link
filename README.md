# gpx-link

View **GPX waypoints** on an **OpenStreetMap** basemap (Leaflet), **zoomed to fit** all points from one or more files. Tracks and routes in the GPX are drawn as lines; waypoint markers remain clickable links to **Google Maps** (exact coordinates so the pin matches the GPX data; you can explore nearby places there or start navigation).

The project keeps a **small, tested core library** and **CLI** separate from the **optional Qt desktop GUI**.

## Requirements

- Python **3.10+**
- CLI and library: [`gpxpy`](https://github.com/tkrajina/gpxpy)
- GUI (optional): [PySide6](https://wiki.qt.io/Qt_for_Python) with **Qt WebEngine** (for the embedded map)

## Install

### From PyPI (released package)

Install into a virtual environment when you can (recommended). After a successful install, the **`gpx-link`** and **`gpx-link-gui`** entry points must be on the environment’s **`PATH`** (for example `.venv/bin` if you activated a venv, or your user scripts directory for `pip install --user`).

**CLI only** (listing waypoints JSON and emitting HTML maps):

```bash
python -m pip install gpx-link
```

Upgrade an existing install:

```bash
python -m pip install --upgrade gpx-link
```

**CLI + desktop GUI** (Linux, macOS, Windows):

```bash
python -m pip install 'gpx-link[gui]'
```

If you manage packages with **[uv](https://docs.astral.sh/uv/)** globally or inside a project venv, the same installs work with **`uv pip`** (they still pull from PyPI):

```bash
uv pip install gpx-link
uv pip install 'gpx-link[gui]'
```

Sanity check for the CLI:

```bash
gpx-link --help
```

After installing **`[gui]`**, run **`gpx-link-gui`** once to confirm the desktop app starts.

If **`gpx-link`** is **not found**, the **`bin`** (or Scripts) folder for that environment is probably not on **`PATH`**. Either activate your venv, or call the CLI with the same interpreter you used for pip: **`python -m gpx_link`**.

### From a clone (local / development)

Inside the repository, **[uv](https://docs.astral.sh/uv/)** is the smoothest workflow: **`uv sync`** creates or updates **`.venv`**, installs the package in editable form, and pulls **dev** dependencies from **`pyproject.toml`**.

```bash
git clone <your-fork-or-upstream-url> gpx-link
cd gpx-link
uv sync
```

Add the GUI stack when you work on **`gpx_link.gui`**:

```bash
uv sync --extra gui
```

**Run commands without activating the venv** (uv injects the project environment):

```bash
uv run gpx-link list track1.gpx
uv run gpx-link html tour.gpx -o map.html
uv run gpx-link-gui ./routes/*.gpx
```

**Run after activating `.venv`** (Unix example):

```bash
source .venv/bin/activate
gpx-link list track1.gpx
gpx-link-gui
```

**Pure pip / editable**, if you prefer not to use uv on a checkout:

```bash
python -m pip install -e .
python -m pip install -e '.[gui]'
```

Contribution workflow (hooks, commits, pytest): **[CONTRIBUTING.md](CONTRIBUTING.md)**.

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

If **`gpx-link-gui`** is missing or **`ImportError`** appears, install the **`gui`** extra (`pip install 'gpx-link[gui]'` or `uv sync --extra gui` on a checkout) and ensure **Qt WebEngine** is supported on your platform.

## Behavior and URLs

- **Map**: OpenStreetMap tiles via Leaflet (`html_map.build_leaflet_html`).
- **Fit**: Bounds are computed from waypoints and track/route points, then padded so the view is not flush against the edges.
- **Google Maps**: Named waypoints use a search URL centered on the GPX coordinates (`/maps/search/{name}/@{lat},{lng},17z`) so Maps can match the nearby POI. Empty or generic names (`Waypoint`) still use a coordinate-only Maps URL API link for an exact pin.

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

See **[CONTRIBUTING.md](CONTRIBUTING.md)** for pre-commit, Conventional Commits, and full setup.

## Release tooling

Version bumps and changelogs can be managed with **python-semantic-release** (see `pyproject.toml` and `CHANGELOG.md`). Publishing to PyPI expects your own Trusted Publisher and GitHub environment setup.

## License

See [LICENSE](LICENSE).
