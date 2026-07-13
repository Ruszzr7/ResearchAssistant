#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR/.."
command -v docker >/dev/null 2>&1 || { echo "[ERROR] Docker CLI not found." >&2; exit 1; }
[[ -f .env ]] || { echo "[ERROR] .env not found." >&2; exit 1; }
mkdir -p backups
stamp="$(date +%Y%m%d_%H%M%S)"
out="backups/research_assistant_${stamp}.sql"
part="${out}.part"
trap 'rm -f "$part"' ERR
docker compose exec -T mysql sh -c 'exec mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" --single-transaction --routines --events "$MYSQL_DATABASE"' > "$part"
mv "$part" "$out"
echo "[OK] MySQL backup written to $out"
