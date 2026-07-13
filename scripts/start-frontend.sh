#!/usr/bin/env bash
# 启动前端开发服务器
set -e

GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m'

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
FRONTEND_DIR="$(cd "$SCRIPT_DIR/../frontend" && pwd)"

# 检查端口是否已被占用
if netstat -ano 2>/dev/null | grep -Eq ':5173[[:space:]].*LISTENING'; then
  echo -e "${YELLOW}5173 端口已被占用，前端开发服务器可能已经在运行${NC}"
  echo "请访问 http://localhost:5173"
  exit 0
fi

NPM_CMD="$(command -v npm.cmd || command -v npm || true)"
if [ -z "$NPM_CMD" ]; then
  echo -e "${RED}未找到 npm，请先安装 Node.js${NC}"
  exit 1
fi

cd "$FRONTEND_DIR"

if [ ! -d "$FRONTEND_DIR/node_modules" ]; then
  echo "检测到 frontend/node_modules 不存在，先执行 npm install..."
  "$NPM_CMD" install
fi

echo -e "${GREEN}正在启动前端开发服务器...${NC}"
"$NPM_CMD" run dev
