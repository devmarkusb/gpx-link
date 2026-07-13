# gpx-link

[![PyPI version](https://img.shields.io/pypi/v/gpx-link?label=PyPI)](https://pypi.org/project/gpx-link/)
[![Python versions](https://img.shields.io/python/required-version-toml?tomlFilePath=https://raw.githubusercontent.com/devmarkusb/gpx-link/main/pyproject.toml&label=Python)](https://pypi.org/project/gpx-link/)
[![CI workflow](https://img.shields.io/badge/CI-ci.yml-2088FF?logo=githubactions&logoColor=white)](https://github.com/devmarkusb/gpx-link/actions/workflows/ci.yml)
[![Android Play workflow](https://img.shields.io/badge/Google_Play-release_workflow-3DDC84?logo=googleplay&logoColor=white)](https://github.com/devmarkusb/gpx-link/actions/workflows/android-play.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-informational)](LICENSE)

View **GPX waypoints** on an **OpenStreetMap** basemap (Leaflet), **zoomed to fit** all points from one or more files. Tracks and routes are drawn as paths (**polylines**; a single-point segment appears as a small circle marker). **Waypoint marker clicks** open **Google Maps** in a new tab or your default browser; how the link is built depends on the waypoint name (see **Behavior and URLs**).

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
git clone https://github.com/devmarkusb/gpx-link.git gpx-link
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
# One JSON object per waypoint (source, name, latitude, longitude, elevation_m, description)
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

Use **Import GPX…** to add GPX files to a sidebar list; check or uncheck files to control what is combined on the map. The map pane embeds the same HTML as the CLI `html` command. Initial paths from the command line are added the same way.

If **`gpx-link-gui`** is missing or **`ImportError`** appears, install the **`gui`** extra (`pip install 'gpx-link[gui]'` or `uv sync --extra gui` on a checkout) and ensure **Qt WebEngine** is supported on your platform.

### Desktop ads

The desktop GUI can show a bottom banner ad in a separate Qt WebEngine view when
release packaging provides an approved ad placement. Configure either a remote
banner page or inline HTML:

```bash
GPX_LINK_DESKTOP_AD_URL="https://example.com/gpx-link/banner.html" gpx-link-gui

GPX_LINK_DESKTOP_AD_HTML_FILE="/path/to/banner.html" gpx-link-gui
```

Optional desktop monetization settings:

- **`GPX_LINK_DESKTOP_AD_HTML`** — inline banner HTML instead of a file.
- **`GPX_LINK_DESKTOP_AD_HEIGHT`** — banner height in pixels, clamped to 48–160.
- **`GPX_LINK_DESKTOP_REMOVE_ADS_URL`** — URL opened by **Remove ads…** in the project menu.
- **`GPX_LINK_DESKTOP_AD_FREE=1`** — disables the desktop ad slot for paid or ad-free builds.

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
| `android/` | Chaquopy-based **Maps GPX** Android app (Play listing name; `applicationId` **`org.cismypa.gpxlink`**) |

## Android app and Google Play

There is a native **Android** wrapper under **`android/`** (Kotlin + [Chaquopy](https://chaquo.com/chaquopy/)). The on-device and Play Store listing name is **Maps GPX**; the Python package and repo remain **gpx-link**, and the Android application id is **`org.cismypa.gpxlink`**.

**`versionName`** and **`versionCode`** come from **`pyproject.toml`** (`project.version`), so bump the Python package version before a store release.

**Store listing text and graphics** for English (US) live under **`android/fastlane/metadata/android/en-US/`**: `title.txt`, `short_description.txt`, `full_description.txt`, optional `promotional_text.txt`, release notes in **`changelogs/<versionCode>.txt`**, and bitmaps under **`images/`** (512×512 icon, 1024×500 feature graphic, phone screenshots). Maintainer notes and alternate titles are in **`play_store_notes.txt`**. Regenerate Play listing PNGs and desktop GUI icons anytime:

```bash
uv run --with pillow python scripts/generate_brand_assets.py
```

CI builds **debug** APKs on every push/PR; **release** signing and uploads are documented in **[CONTRIBUTING.md](CONTRIBUTING.md)** (GitHub secrets, tags, Fastlane, Play Console).

## Development

```bash
uv sync
uv audit --preview-features audit --locked
uv run pytest
uv run ruff check
uv run ruff format --check
```

See **[CONTRIBUTING.md](CONTRIBUTING.md)** for pre-commit and full setup.

## Release tooling

Version bumps and changelogs can be managed with **python-semantic-release** (see `pyproject.toml` and `CHANGELOG.md`). Publishing to PyPI expects your own Trusted Publisher and GitHub environment setup.

Publishing the **Android** build to **Google Play** uses the **`Android Play release`** workflow (`.github/workflows/android-play.yml`): after **semantic-release** on **`main`** it is started automatically from the **Release** workflow, or you can push a **`v*`** tag / run the workflow manually from the Actions tab. Manual runs can enable **Push fastlane metadata to Play** to upload listing copy and graphics from **`android/fastlane/metadata/`** together with the **AAB**. See **[CONTRIBUTING.md — Android builds and Google Play](CONTRIBUTING.md#android-builds-and-google-play)** for secrets, keystores, the Fastlane file map, and the Play Console checklist.

## License

See [LICENSE](LICENSE).
