#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "Usage: $0 backups/research_assistant_YYYYMMDD_HHMMSS.sql" >&2
  exit 1
fi
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR/.."
if [[ ! -f "$1" ]]; then
  echo "[ERROR] Backup file not found: $1" >&2
  exit 1
fi
echo "[WARN] Restore replaces current database contents. Press Ctrl+C to cancel."
read -r -p "Type RESTORE to continue: " confirmation
[[ "$confirmation" == "RESTORE" ]]
docker compose exec -T mysql sh -c 'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE"' < "$1"
echo "[OK] Database restored. Restart backend and run the RAG consistency check."
