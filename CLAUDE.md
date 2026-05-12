# Claude Code — gpx-link

**Canonical project instructions:** [`AGENTS.md`](AGENTS.md). Read it before substantive edits; it lists exact build, test, lint commands and paths that must not be touched.

## Claude-specific notes

- Prefer **`uv run …`** for Python tooling so commands use this repo’s **`.venv`** without relying on a prior `activate`.
- Local **`.claude/`** state remains gitignored (see **`.gitignore`**); share durable guidance by updating **`AGENTS.md`**, not only local transcripts.
