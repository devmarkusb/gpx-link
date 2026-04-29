# py-app-template

A general-purpose Python project template.

## Release prerequisites

This repository uses python-semantic-release (PSR) to manage versioning and changelogs. Before enabling automatic publishing:

- Register the project as a PyPI Trusted Publisher and ensure a pending publisher exists for the GitHub organization.
- Create a `pypi` GitHub environment and populate any required secrets.
- Add `CODECOV_TOKEN` to the repository secrets for Codecov uploads on the Python 3.13 CI leg.

See CHANGELOG.md for the PSR-managed changelog.

## Badges

Replace `OWNER` and `REPO` after forking. Static placeholders below link to this section until then.

[![CI](https://img.shields.io/badge/ci-passing-brightgreen)](#badges) [![Coverage](https://img.shields.io/badge/coverage-100%25-brightgreen)](#badges) [![PyPI](https://img.shields.io/badge/pypi-0.1.0-blue)](#badges) [![Python Versions](https://img.shields.io/badge/python-3.10--3.13-orange)](#badges) [![License](https://img.shields.io/badge/license-MIT-blue)](#badges)

## First Steps (Rename checklist)

After cloning, rename the template to your project:

- Replace the package and distribution name across the repo (example for macOS; use `sed -i` without `''` on GNU/Linux):

  ```bash
  find . -type f \( -name "*.py" -o -name "*.toml" -o -name "*.yml" -o -name "*.md" \) \
    ! -path "./.git/*" ! -path "./.venv/*" \
    -exec sed -i '' \
    -e 's/py_app_template/your_package_name/g' \
    -e 's/py-app-template/your-package-name/g' {} +
  ```

- Update AUTHOR and email in pyproject.toml
- Update project description and version in pyproject.toml
