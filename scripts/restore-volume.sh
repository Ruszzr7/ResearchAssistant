#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo "Usage: $0 research-assistant-papers-data backups/research-assistant-papers-data_YYYYMMDD_HHMMSS.tar.gz" >&2
  exit 1
fi
volume="$1"
archive="$2"
case "$volume" in
  research-assistant-papers-data|research-assistant-qdrant-data) ;;
  *) echo "[ERROR] Unsupported volume: $volume" >&2; exit 1 ;;
esac
command -v docker >/dev/null 2>&1 || { echo "[ERROR] Docker CLI not found." >&2; exit 1; }
[[ -f "$archive" ]] || { echo "[ERROR] Archive not found: $archive" >&2; exit 1; }
read -r -p "Restore replaces volume contents. Type RESTORE to continue: " confirmation
[[ "$confirmation" == "RESTORE" ]]
archive_name="$(basename "$archive")"
docker run --rm -e "ARCHIVE_NAME=$archive_name" -v "${volume}:/target" -v "$(cd "$(dirname "$archive")" && pwd):/backup:ro" alpine:3.20 \
  sh -c 'rm -rf /target/* /target/.[!.]* /target/..?* 2>/dev/null || true; tar xzf "/backup/$ARCHIVE_NAME" -C /target'
echo "[OK] Volume restored: $volume"
