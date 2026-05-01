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

## Notes

- Ensure `.secrets.baseline` exists before running `pre-commit install` to avoid the detect-secrets hook blocking commits.
- After cloning, run both `pre-commit install` and `pre-commit install --hook-type commit-msg` (see **Development setup** above).
