#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR/.."
command -v docker >/dev/null 2>&1 || { echo "[ERROR] Docker CLI not found." >&2; exit 1; }
mkdir -p backups
stamp="$(date +%Y%m%d_%H%M%S)"

for volume in research-assistant-papers-data research-assistant-qdrant-data; do
  if ! docker volume inspect "$volume" >/dev/null 2>&1; then
    echo "[WARN] Volume not found, skipping: $volume" >&2
    continue
  fi
  out="backups/${volume}_${stamp}.tar.gz"
  docker run --rm -v "${volume}:/source:ro" -v "$(pwd)/backups:/backup" alpine:3.20 \
    tar czf "/backup/$(basename "$out")" -C /source .
  echo "[OK] Volume backup written to $out"
done
