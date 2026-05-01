# Contributing

## Development setup

1. Install [uv](https://docs.astral.sh/uv/).
2. Clone the repo, then from the repo root:

   ```bash
   uv sync
   ```

   That creates or refreshes **`.venv`**, installs **gpx-link** in editable mode, and installs **dependency-groups › dev** (pytest, ruff, pre-commit tooling, etc.; see **`pyproject.toml`**).
3. **[Optional]** If you touch the GUI or need to reproduce WebEngine behaviour locally:

   ```bash
   uv sync --extra gui
   ```

   This adds **PySide6** alongside the core + dev stacks.
4. Generate the secrets baseline (first time only, if `.secrets.baseline` does not exist):

   ```bash
   detect-secrets scan > .secrets.baseline
   git add .secrets.baseline
   ```

5. Install pre-commit hooks:

   ```bash
   uv run pre-commit install
   ```

   Alternatively, activate **`.venv`** and run **`pre-commit install`** without the **`uv run`** prefix.

## Running CLI and GUI locally

- **Prefer `uv run …`** so you always use this repo’s **`.venv`** without activating it:

  ```bash
  uv run gpx-link --help
  uv run gpx-link-gui
  ```

- After **`source .venv/bin/activate`** (or your platform’s equivalent), plain **`gpx-link`** / **`gpx-link-gui`** work the same way.
- These commands are **not** the same as installing from **PyPI** into some other environment: here you are running the **editable checkout** locked by **`uv.lock`**.

## PyPI install (for comparison / dogfooding releases)

To mimic end users without the checkout on **`PYTHONPATH`**:

```bash
uv venv /tmp/gpx-link-pypi
source /tmp/gpx-link-pypi/bin/activate   # adjust for Windows
python -m pip install gpx-link          # or: pip install 'gpx-link[gui]'
gpx-link --help
```

Use **`python -m pip install --upgrade gpx-link`** to refresh after a new release.

## Running tests

```bash
uv run pytest --cov
```

## Android builds and Google Play

### Local builds

- Install **JDK 17** (matches CI and the Android Gradle Plugin).
- From **`android/`**:

  ```bash
  chmod +x gradlew   # once, if needed
  ./gradlew assembleDebug
  ```

  Debug APK output: **`android/app/build/outputs/apk/debug/`**.

- **Release** builds need **`android/keystore.properties`** and an upload keystore file (paths relative to **`android/`**). Copy **`android/keystore.properties.example`** to **`keystore.properties`**, fill in passwords and alias, and point **`storeFile`** at your **`.jks`**. Those files are **gitignored**; never commit them.

  ```bash
  ./gradlew bundleRelease assembleRelease
  ```

  Signed **AAB** (what Play expects for new uploads): **`app/build/outputs/bundle/release/app-release.aab`**. Signed release APK: **`app/build/outputs/apk/release/app-release.apk`**.

On macOS, prefer **Ruby 3.3+** if you run **Fastlane** locally (`bundle install` in **`android/`**); CI uses **`ruby/setup-ruby`** with Bundler **2.5.23**.

### CI: `.github/workflows/android-play.yml`

Triggers on **`v*`** tags (for example **`v1.2.0`**, aligned with **python-semantic-release**) or **manual `workflow_dispatch`**.

**Required repository secrets** for a signed release build:

| Secret | Purpose |
| ------ | ------- |
| **`ANDROID_KEYSTORE_BASE64`** | Base64-encoded upload keystore (`.jks`) |
| **`ANDROID_KEYSTORE_PASSWORD`** | Keystore password |
| **`ANDROID_KEY_ALIAS`** | Signing key alias |
| **`ANDROID_KEY_PASSWORD`** | Key password (often the same as the keystore password for PKCS12-style keystores) |

Optional: **`PLAY_SERVICE_ACCOUNT_JSON`** — JSON key for the Play Developer API. If unset, the workflow still builds and uploads artifacts to the GitHub run (and attaches **AAB/APK** to the GitHub **Release** when the ref is a **`v*`** tag); it **skips** calling Fastlane upload.

Manual runs can choose Play **track** (`internal`, `alpha`, `beta`, `production`) and optionally push Fastlane **metadata** (`title`, short/long descriptions under **`android/fastlane/metadata/`**).

### Play Console checklist (maintainers)

1. Create the app listing if it does not exist; accept Play App Signing and register your **upload** certificate.
2. In **API access**, link a Google Cloud project and create a **service account** with permission to release; download JSON into **`PLAY_SERVICE_ACCOUNT_JSON`**.
3. Complete **store listing**, **content rating**, **target audience**, **privacy policy** / **data safety** as required (the app declares **`INTERNET`**).
4. Promote builds from **internal testing** upward once binaries install and behave as expected.

## Notes

- Ensure `.secrets.baseline` exists before running `pre-commit install` to avoid the detect-secrets hook blocking commits.
- After cloning, run both `pre-commit install` and `pre-commit install --hook-type commit-msg` (see **Development setup** above).
