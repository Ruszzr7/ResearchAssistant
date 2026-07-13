#!/usr/bin/env bash
# 检查项目运行环境：JDK、Maven、MySQL、Node.js
set -u

GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m'

ok() { echo -e "${GREEN}✓${NC} $1"; }
warn() { echo -e "${YELLOW}⚠${NC} $1"; }
failures=0
err() { echo -e "${RED}✗${NC} $1"; failures=$((failures + 1)); }

echo "========== Research Assistant 环境检查 =========="
echo ""

# JDK
if [ -n "${JAVA_HOME:-}" ] && { [ -f "$JAVA_HOME/bin/java.exe" ] || [ -f "$JAVA_HOME/bin/java" ]; }; then
  ok "JAVA_HOME: $JAVA_HOME"
  if [ -f "$JAVA_HOME/bin/java.exe" ]; then
    "$JAVA_HOME/bin/java.exe" -version 2>&1 | head -1
  else
    "$JAVA_HOME/bin/java" -version 2>&1 | head -1
  fi
elif command -v java.exe > /dev/null 2>&1; then
  warn "JAVA_HOME 未设置，但 PATH 中找到了 java"
  java.exe -version 2>&1 | head -1
elif command -v java > /dev/null 2>&1; then
  warn "JAVA_HOME 未设置，但 PATH 中找到了 java"
  java -version 2>&1 | head -1
else
  java_found=""
  for base in /c/tools "/c/Program Files/Eclipse Adoptium" "/c/Program Files/Java"; do
    if [ -d "$base" ]; then
      java_found=$(find "$base" -maxdepth 4 -path "*/bin/java.exe" -type f 2>/dev/null | head -n 1)
      if [ -n "$java_found" ]; then
        ok "扫描到 JDK: $(dirname "$(dirname "$java_found")")"
        break
      fi
    fi
  done
  if [ -z "$java_found" ]; then
    err "未找到 JDK 17+"
  fi
fi

# Maven（通过 mvnw 判断）
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [ -f "$SCRIPT_DIR/../backend/mvnw" ]; then
  ok "后端 Maven Wrapper 存在: backend/mvnw"
else
  err "未找到 backend/mvnw"
fi

# MySQL
mysql_found=""
if [ -n "${MYSQL_HOME:-}" ] && { [ -f "$MYSQL_HOME/bin/mysqld.exe" ] || [ -f "$MYSQL_HOME/bin/mysqld" ]; }; then
  mysql_found="$MYSQL_HOME/bin/mysqld.exe"
  ok "MYSQL_HOME: $MYSQL_HOME"
elif command -v mysqld.exe > /dev/null 2>&1; then
  mysql_found=$(command -v mysqld.exe)
  ok "PATH 中找到 MySQL: $mysql_found"
elif command -v mysqld > /dev/null 2>&1; then
  mysql_found=$(command -v mysqld)
  ok "PATH 中找到 MySQL: $mysql_found"
else
  for base in /c/tools /c/xampp /d/xampp; do
    if [ -d "$base" ]; then
      found=$(find "$base" -maxdepth 4 -name "mysqld.exe" -type f 2>/dev/null | head -n 1)
      if [ -n "$found" ]; then
        mysql_found="$found"
        ok "扫描到 MySQL: $found"
        break
      fi
    fi
  done
fi
if [ -z "$mysql_found" ]; then
  err "未找到 MySQL"
fi

# Node.js
if command -v node.exe > /dev/null 2>&1; then
  ok "Node.js: $(node.exe --version)"
elif command -v node > /dev/null 2>&1; then
  ok "Node.js: $(node --version)"
else
  err "未找到 Node.js（前端需要）"
fi

if command -v npm.cmd > /dev/null 2>&1 || command -v npm > /dev/null 2>&1; then
  ok "npm 可用"
else
  err "未找到 npm（前端需要）"
fi

# 端口占用
echo ""
echo "端口检查:"
if netstat -ano 2>/dev/null | grep -Eq ':3306[[:space:]].*LISTENING'; then
  ok "3306 (MySQL) 已监听"
else
  warn "3306 (MySQL) 未监听"
fi

if netstat -ano 2>/dev/null | grep -Eq ':8080[[:space:]].*LISTENING'; then
  ok "8080 (后端) 已监听"
else
  warn "8080 (后端) 未监听"
fi

echo ""
echo "=================================================="
echo "如果上面有 ✗，请先安装对应软件或设置环境变量。"
echo "如果只有 ⚠，可以使用 scripts/start-all.sh 自动启动。"
echo "=================================================="

if [ "$failures" -gt 0 ]; then
  exit 1
fi
