#!/usr/bin/env bash
# Fails if any proof test is silenced (security threat E-03).
#
# Chaos and concurrency tests are the highest-likelihood silent failure in this project: they
# are slow, they are flaky if written against transient state, and nothing breaks when they are
# skipped. Making @Disabled a build failure means silencing a proof requires deleting it
# visibly, in a commit, in a public repository.
#
# Scans Java sources only. The first version grepped the whole tree and matched its own
# explanatory comment in a build file — see docs/bug-log.md.
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

if hits=$(grep -rn --include='*.java' -E '^\s*@Disabled' proof/ 2>/dev/null); then
  echo "ERROR: @Disabled found in proof/ — a silenced proof is not a passing proof" >&2
  echo "$hits" >&2
  exit 1
fi
echo "OK: no @Disabled in proof/"
