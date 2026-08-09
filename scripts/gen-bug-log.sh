#!/usr/bin/env bash
# Regenerates docs/bug-log.md from `Bug:` trailers in commit messages (S1-14).
#
# The bug log is the highest-signal artifact in the finished repository (G-6) and was the
# weakest link in the plan, because every mitigation was a form of "be disciplined". Making it
# a byproduct of a commit message you were writing anyway is the only structural fix available.
#
# HONEST LIMIT: the CI check that runs this detects a *stale* log — the failure that actually
# happens. It cannot detect a *missing trailer* on a commit that deserved one. Nothing can.
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"
exec python3 scripts/gen_bug_log.py docs/bug-log.md
