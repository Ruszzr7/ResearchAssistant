#!/usr/bin/env bash
# 一键启动 MySQL + 后端
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

"$SCRIPT_DIR/start-mysql.sh"
echo ""
"$SCRIPT_DIR/start-backend.sh"

echo ""
echo "=================================================="
echo "全部启动完成。"
echo "后端地址: http://localhost:8080"
echo "如需启动前端，请执行: ./scripts/start-frontend.sh"
echo "或一键启动完整开发环境: ./scripts/start-dev.sh"
echo "=================================================="
