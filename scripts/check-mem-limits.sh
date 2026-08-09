#!/usr/bin/env bash
# Asserts every Compose service declares mem_limit.
#
# NFR-011's budget is tight enough that one unlimited container blows it: six JVMs at default
# heap plus Redpanda's default reservation exceed 6 GB before Postgres is counted
# (architecture review F-6). Limits are a requirement here, not a tuning nicety.
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

missing=$(python3 - <<'PY'
import pathlib, re
text = pathlib.Path("docker-compose.yml").read_text()
body = text.split("\nservices:\n", 1)[1].split("\nvolumes:", 1)[0]
blocks = [b for b in re.split(r"\n(?=  [a-z0-9_-]+:\n)", body) if b.strip()]
bad = [b.strip().split(":", 1)[0] for b in blocks if "mem_limit:" not in b]
print(" ".join(bad))
PY
)

if [ -n "$missing" ]; then
  echo "ERROR: Compose services without mem_limit: $missing" >&2
  exit 1
fi
echo "OK: every Compose service declares mem_limit"
