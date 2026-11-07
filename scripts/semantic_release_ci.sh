#!/usr/bin/env bash
# Publish a semantic-release version without pushing the bump commit to main.
#
# The main ruleset requires status checks that a brand-new commit cannot have
# yet, and this user-owned repo cannot grant the GitHub Actions app a bypass.
# Prepare the bump on release/vX.Y.Z, let the required checks run, squash-merge,
# then tag that merge and create the GitHub release. Tag pushes are not covered
# by the branch ruleset.
set -euo pipefail

if [ -z "${GITHUB_OUTPUT:-}" ]; then
  echo "GITHUB_OUTPUT is required" >&2
  exit 1
fi

version_line() {
  uv run python -m semantic_release version "$@" | tail -n 1
}

current_version() {
  uv run python -c 'import tomllib; print(tomllib.load(open("pyproject.toml", "rb"))["project"]["version"])'
}

require_version() {
  local value="$1"
  if [[ ! "$value" =~ ^[0-9]+\.[0-9]+\.[0-9]+([.+-][0-9A-Za-z.-]+)?$ ]]; then
    echo "::error::Unexpected version string: ${value}"
    exit 1
  fi
}

tag_release() {
  echo "Tagging v${next} on $(git rev-parse --short HEAD)"
  uv run python -m semantic_release version --no-commit --skip-build
}

next="$(version_line --print)"
last="$(version_line --print-last-released)"
current="$(current_version)"
require_version "$next"
require_version "$last"
require_version "$current"
echo "current=${current} next=${next} last=${last}"

if [ "$next" = "$last" ]; then
  echo "released=false" >>"$GITHUB_OUTPUT"
  echo "No commits require a release."
  exit 0
fi

if [ "$current" = "$next" ]; then
  tag_release
  exit 0
fi

branch="release/v${next}"
# A rerun can observe that the bump pull request already landed while this
# checkout is still the pre-bump main.
if pr_state="$(gh pr view "$branch" --json state --jq .state 2>/dev/null)" &&
  [ "$pr_state" = "MERGED" ]; then
  git fetch origin main
  git checkout -B main origin/main
  if [ "$(current_version)" = "$next" ]; then
    tag_release
    exit 0
  fi
fi

uv run python -m semantic_release version --no-push --no-tag --no-vcs-release
# That command records released=true before the pull request exists.
echo "released=false" >>"$GITHUB_OUTPUT"

if git ls-remote --exit-code --heads origin "$branch" >/dev/null 2>&1; then
  git fetch origin "+refs/heads/${branch}:refs/remotes/origin/${branch}"
  git push --force-with-lease="refs/heads/${branch}:refs/remotes/origin/${branch}" \
    origin "HEAD:refs/heads/${branch}"
else
  git push origin "HEAD:refs/heads/${branch}"
fi

if ! number="$(gh pr view "$branch" --json number --jq .number 2>/dev/null)"; then
  gh pr create \
    --base main \
    --head "$branch" \
    --title "chore(release): ${next}" \
    --body "Version bump prepared by python-semantic-release. \`main\` requires status checks on the incoming commit, so this pull request stands in for a direct push."
  number="$(gh pr view "$branch" --json number --jq .number)"
fi

pr_url="$(gh pr view "$number" --json url --jq .url)"
echo "Release pull request: ${pr_url}"

# GITHUB_TOKEN pushes do not start pull_request workflows, so start CI directly.
# The check names are what the main ruleset requires.
gh workflow run ci.yml --ref "$branch"
gh pr merge "$number" --auto --squash --subject "chore(release): ${next}" ||
  echo "Auto-merge was not enabled on this attempt; will retry once checks pass."

merged=false
for _ in $(seq 1 60); do
  json="$(gh pr view "$number" --json state,mergeStateStatus,statusCheckRollup)"
  state="$(jq -r .state <<<"$json")"
  merge_state="$(jq -r .mergeStateStatus <<<"$json")"
  if [ "$state" = "MERGED" ]; then
    merged=true
    break
  fi
  if [ "$state" = "CLOSED" ]; then
    echo "::error::Release pull request was closed without merging: ${pr_url}"
    exit 1
  fi
  if [ "$merge_state" = "DIRTY" ]; then
    echo "::error::Release pull request has conflicts: ${pr_url}"
    exit 1
  fi
  failed="$(jq '[.statusCheckRollup[]? | select(.conclusion=="FAILURE" or .conclusion=="CANCELLED" or .conclusion=="TIMED_OUT")] | length' <<<"$json")"
  if [ "$failed" != "0" ]; then
    echo "::error::Status checks failed on ${pr_url}"
    exit 1
  fi
  ready="$(
    jq -r '
      (.statusCheckRollup // []) as $checks
      | [
          "Conventional commits",
          "build (3.11)",
          "build (3.13)",
          "Android (assembleDebug)"
        ] as $required
      | if all(
          $required[];
          . as $name
          | any($checks[]; (.name // .context) == $name and .conclusion == "SUCCESS")
        )
        then "yes"
        else "no"
        end
    ' <<<"$json"
  )"
  if [ "$ready" = "yes" ]; then
    gh pr merge "$number" --squash --subject "chore(release): ${next}" || true
    if [ "$(gh pr view "$number" --json state --jq .state)" = "MERGED" ]; then
      merged=true
      break
    fi
  fi
  sleep 20
done

if [ "$merged" != true ]; then
  echo "::error::Timed out waiting for ${pr_url} to merge"
  exit 1
fi

git fetch origin main
git checkout -B main origin/main
landed="$(current_version)"
if [ "$landed" != "$next" ]; then
  echo "::error::main is at ${landed} after the release pull request merged; expected ${next}"
  exit 1
fi

tag_release
