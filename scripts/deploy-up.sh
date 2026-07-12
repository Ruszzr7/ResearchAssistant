#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR/.."

if [[ ! -f .env ]]; then
  echo "[ERROR] .env not found. Copy .env.example to .env and set RA_MASTER_KEY first." >&2
  exit 1
fi

docker compose up -d --build "$@"
echo "[INFO] Waiting for frontend health..."
for _ in $(seq 1 30); do
  if curl -fsS --max-time 2 http://127.0.0.1:8088 >/dev/null; then
    echo "[OK] ResearchAssistant is ready at http://localhost:8088"
    exit 0
  fi
  sleep 2
done
echo "[WARN] Containers started but frontend is not ready. Run: docker compose logs -f backend" >&2
