#!/usr/bin/env bash
# 一键启动完整开发环境：MySQL + 后端 + 前端
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# MySQL + 后端在后台启动
"$SCRIPT_DIR/start-all.sh"

echo ""
echo "=================================================="
echo "正在启动前端开发服务器..."
echo "=================================================="
echo ""

# 前端在前台启动，方便看到 dev server 输出和 URL
"$SCRIPT_DIR/start-frontend.sh"
