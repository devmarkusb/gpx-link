# Agent instructions — gpx-link

Portable project rules for AI coding agents. **This file is the canonical source of truth** for repository-specific behavior. Cursor-specific rules under `.cursor/rules/` only point here.

## 1. Project overview

**gpx-link** is a Python **3.10+** package (`gpx-link` on PyPI): parse GPX files, build an OSM/Leaflet HTML map, and open Google Maps URLs for waypoints. Layout:

- **`src/gpx_link/`** — library, CLI (`gpx_link.cli`), optional Qt GUI (`gpx_link.gui`) behind the **`gui`** extra.
- **`tests/`** — pytest suite for core behavior (CLI, parser, bounds, maps URLs, HTML map, simplify).
- **`android/`** — **Maps GPX** Android app (`applicationId` **`org.cismypa.gpxlink`**, Kotlin + [Chaquopy](https://chaquo.com/chaquopy/)); embeds a synced copy of Python sources (see **`android/app/build.gradle.kts`** / `syncGpxLinkPython`).
- **`scripts/`** — maintainer utilities (e.g. Play store asset generation).

Detailed contributor workflow: **[CONTRIBUTING.md](CONTRIBUTING.md)**. User-facing overview: **[README.md](README.md)**.

## 2. Build commands

From the repo root (after **[uv](https://docs.astral.sh/uv/)** is installed):

| Action | Command |
| ------ | ------- |
| Install deps + editable package + dev group | `uv sync` |
| Add optional GUI stack (PySide6) | `uv sync --extra gui` |
| Build sdist/wheel (release layout) | `uv build` |

**Android** (JDK **17**, from **`android/`**): `./gradlew assembleDebug` — debug APK under **`android/app/build/outputs/apk/debug/`**. (Use `chmod +x gradlew` once if needed.)

## 3. Test commands

| Scope | Command |
| ----- | ------- |
| Unit tests + coverage (matches CI) | `uv run pytest` |
| Dependency vulnerability audit (CI) | `uv audit --preview-features audit --locked` |

CI also runs the same suite and dependency audit on Python **3.10** and **3.13** (`.github/workflows/ci.yml`).

## 4. Formatting and linting

| Check | Command |
| ----- | ------- |
| Ruff lint (CI) | `uv run ruff check` |
| Ruff format check (CI) | `uv run ruff format --check` |
| Auto-fix + format locally | `uv run ruff check --fix` and `uv run ruff format` |
| Full local hook suite (ruff, yaml/toml, detect-secrets, markdownlint, codespell, etc.) | `uv run pre-commit run --all-files` |

Commit messages on PRs are validated with **conventional-pre-commit** (see `.pre-commit-config.yaml` and CI **`commit-messages`** job).

## 5. Architecture and important directories

| Path | Notes |
| ---- | ----- |
| `src/gpx_link/parser.py`, `models.py`, `bounds.py`, `maps_urls.py`, `html_map.py`, `simplify.py` | Core library surface |
| `src/gpx_link/cli.py` | `gpx-link` CLI (`list`, `html`) |
| `src/gpx_link/gui/` | Desktop GUI; requires **`[gui]`** / `uv sync --extra gui` |
| `android/app/src/main/java/` | Kotlin Android UI and Chaquopy bridge |
| `android/app/src/main/python/` | On-device Python (e.g. `bridge.py`); **do not hand-edit** synced `gpx_link` tree — see §8 |
| `android/fastlane/metadata/` | Play store listing copy and images |
| `.secrets.baseline` | detect-secrets baseline; required before pre-commit (see CONTRIBUTING) |

## 6. Coding conventions

- **Ruff**: `line-length` 88, `target-version` py310, lint rules **E, F, I, UP, B** (`pyproject.toml`).
- **Imports / style**: follow existing modules; avoid drive-by refactors unrelated to the task.
- **Android + Python**: changing the published Python API or packaged files may require Gradle sync / Chaquopy packaging awareness — read `android/app/build.gradle.kts` when touching cross-layer behavior.
- **Versioning**: `project.version` in **`pyproject.toml`** drives package version and Android `versionName` / `versionCode` expectations for releases.

## 7. Testing expectations

- Prefer **pytest** additions or updates for behavior changes in **`src/gpx_link/`** (excluding GUI-heavy paths that lack coverage in CI).
- Run **`uv run pytest`** before concluding work on Python changes.
- GUI (`gpx_link.gui`) has **minimal automated coverage** in CI; manual smoke (`uv run gpx-link-gui`) may be needed when changing Qt/WebEngine code.

## 8. Files and directories agents must not edit without explicit approval

- **`uv.lock`** — lockfile; do not churn for unrelated edits.
- **`android/app/src/main/python/gpx_link/`** — **generated/synced** from `src/gpx_link` by the Android build; edit **`src/gpx_link`** instead.
- **`android/vendor/`**, **`android/.bundle/`** — vendored / local Bundler state.
- **Keystores, `android/keystore.properties`, `android/*.jks`, `android/play-console-service-account.json`, `android/local.properties`** — secrets and machine-local paths.
- **`.github/workflows/release.yml`** / **`android-play.yml`** — release and Play deployment pipelines; treat as high-risk.
- **Generated Play changelogs** — paths ignored per `.gitignore` under `android/fastlane/metadata/android/*/changelogs/[0-9]*.txt` (generated listing flow); understand **`Fastfile`** before changing that pipeline.

## 9. Security and privacy constraints

- **Never** commit credentials, keystore passwords, API keys, or service-account JSON. Repository uses **detect-secrets** with **`.secrets.baseline`**.
- GPX files and maps may contain **personal location data** — do not exfiltrate or log full user tracks in examples.
- Android release builds use AdMob / billing identifiers from CI secrets or Gradle defaults — see **CONTRIBUTING.md** for what is production-sensitive.

## 10. Review checklist before final response

- [ ] Edits align with **`src/`** vs **`android/`** ownership rules above; no accidental edits to generated trees or lockfiles.
- [ ] **`uv run ruff check`**, **`uv run ruff format --check`**, **`uv run pytest`**, and **`uv audit --preview-features audit --locked`** were run (or failures explained) for Python-affecting changes.
- [ ] If hooks matter for the change: **`uv run pre-commit run --all-files`** (or relevant hooks) was considered.
- [ ] Android-only changes: consider **`./gradlew assembleDebug`** from **`android/`** (same as CI **android** job); not re-run here if not verified in-session.
- [ ] No secrets or **production-only** config committed; no broad permission requests unless the user asked for them.

---

## Maintenance policy (stacking)

| Layer | What belongs |
| ----- | ------------ |
| **Global / user** | Tool permissions, personal MCP servers, IDE preferences. |
| **Repo root** | **`AGENTS.md`**, thin **`CLAUDE.md`**, **`.cursor/rules/*.mdc`** adapters — shared, reviewable instructions. |
| **Nested `AGENTS.md`** | Only if a subtree gains a **materially different** toolchain than this Python + Android split; not required today. |
| **Session / chat** | Task-specific goals, one-off constraints, and “do X only” instructions. |

Do not duplicate long policy across layers: update **`AGENTS.md`** first, then keep other files as pointers.
