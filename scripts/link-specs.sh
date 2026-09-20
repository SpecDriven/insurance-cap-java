#!/usr/bin/env bash
# Symlinks the sibling specs-insurance-large repo's specs/ directory as specs/,
# so its contents appear directly as specs/<file>.
# Idempotent: safe to re-run; replaces a stale link if present.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LINK_PATH="$REPO_ROOT/specs"
# Relative target so the link survives moving the parent directory as a whole.
# Note the trailing /specs: that repo nests its feature files under specs/,
# unlike the original specs-insurance repo which held them at its root.
TARGET="../specs-insurance-large/specs"

if [ ! -d "$REPO_ROOT/$TARGET" ]; then
  echo "error: expected spec tree at $(dirname "$REPO_ROOT")/specs-insurance-large/specs" >&2
  exit 1
fi

# Only ever replace a symlink — never clobber a real specs/ directory
if [ -e "$LINK_PATH" ] && [ ! -L "$LINK_PATH" ]; then
  echo "error: $LINK_PATH exists and is not a symlink; remove it first" >&2
  exit 1
fi

ln -sfn "$TARGET" "$LINK_PATH"
echo "linked: $LINK_PATH -> $TARGET"
