# Contributing

## Development Setup

1. Install [uv](https://docs.astral.sh/uv/).
2. Clone the repo and run `uv sync`.
3. Generate the secrets baseline (first time only, if `.secrets.baseline` not existing):

   ```bash
   detect-secrets scan > .secrets.baseline
   git add .secrets.baseline
   ```

4. Install pre-commit hooks:

   ```bash
   pre-commit install
   pre-commit install --hook-type commit-msg
   ```

All commits must follow [Conventional Commits](https://www.conventionalcommits.org/) format.

## Running tests

- uv run pytest --cov

## Notes

- Ensure `.secrets.baseline` exists before running `pre-commit install` to avoid the detect-secrets hook blocking commits.
- After cloning, run both `pre-commit install` and `pre-commit install --hook-type commit-msg`.
