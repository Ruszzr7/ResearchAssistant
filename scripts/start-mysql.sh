#!/usr/bin/env bash
# 启动 MySQL（自动探测常见安装路径）
set -e

# 颜色输出
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# 常见 MySQL 安装路径（按概率排序）
MYSQL_CANDIDATES=(
  "/c/tools/mysql-8.0.28-winx64/bin/mysqld.exe"
  "/c/tools/mysql-8.0.33-winx64/bin/mysqld.exe"
  "/c/tools/mysql-8.0.39-winx64/bin/mysqld.exe"
  "/c/tools/mysql-8.0.40-winx64/bin/mysqld.exe"
  "/c/tools/mysql-8.0.41-winx64/bin/mysqld.exe"
  "/c/tools/mariadb-10.11.8-winx64/bin/mysqld.exe"
  "/c/xampp/mysql/bin/mysqld.exe"
  "/d/xampp/mysql/bin/mysqld.exe"
)

find_mysql() {
  # 1. 环境变量 MYSQL_HOME
  if [ -n "$MYSQL_HOME" ] && [ -f "$MYSQL_HOME/bin/mysqld.exe" ]; then
    echo "$MYSQL_HOME/bin/mysqld.exe"
    return
  fi

  # 2. PATH 中已有 mysqld
  if command -v mysqld.exe &> /dev/null; then
    command -v mysqld.exe
    return
  fi

  # 3. 扫描候选路径
  for candidate in "${MYSQL_CANDIDATES[@]}"; do
    if [ -f "$candidate" ]; then
      echo "$candidate"
      return
    fi
  done

  # 4. 通配扫描（较慢，兜底）
  for base in /c/tools /c/Program\ Files /c/Program\ Files\ \(x86\) /c/xampp /d/xampp; do
    if [ -d "$base" ]; then
      found=$(find "$base" -maxdepth 4 -name "mysqld.exe" -type f 2>/dev/null | head -n 1)
      if [ -n "$found" ]; then
        echo "$found"
        return
      fi
    fi
  done
}

MYSQLD=$(find_mysql)

if [ -z "$MYSQLD" ]; then
  echo -e "${RED}未找到 MySQL 的 mysqld.exe${NC}"
  echo "请先安装 MySQL，或者设置环境变量 MYSQL_HOME 指向安装目录"
  echo "常见位置："
  echo "  - C:\\tools\\mysql-8.0.28-winx64"
  echo "  - C:\\xampp\\mysql"
  exit 1
fi

MYSQL_BIN=$(dirname "$MYSQLD")
MYSQL_HOME=$(dirname "$MYSQL_BIN")

echo -e "${GREEN}找到 MySQL: $MYSQLD${NC}"

# 检查是否已经在运行
if tasklist | grep -i "mysqld.exe" > /dev/null; then
  echo -e "${YELLOW}MySQL 已经在运行中，无需重复启动${NC}"
  exit 0
fi

# 启动 MySQL
LOG_DIR="$MYSQL_HOME/data"
if [ ! -d "$LOG_DIR" ]; then
  LOG_DIR="$MYSQL_HOME"
fi

echo "正在启动 MySQL..."
nohup "$MYSQLD" --console > "$MYSQL_HOME/mysql.log" 2>&1 &
sleep 3

if tasklist | grep -i "mysqld.exe" > /dev/null; then
  echo -e "${GREEN}MySQL 启动成功${NC}"
else
  echo -e "${RED}MySQL 启动失败，请查看日志: $MYSQL_HOME/mysql.log${NC}"
  exit 1
fi
echo "[INFO] Waiting for MySQL readiness..."
for i in $(seq 1 30); do
  if command -v mysqladmin.exe >/dev/null 2>&1 && mysqladmin.exe --protocol=tcp -h 127.0.0.1 -P 3306 ping --silent >/dev/null 2>&1; then
    echo "[OK] MySQL is ready."
    exit 0
  elif ! command -v mysqladmin.exe >/dev/null 2>&1 && (echo >/dev/tcp/127.0.0.1/3306) >/dev/null 2>&1; then
    echo "[OK] MySQL TCP port is ready."
    exit 0
  fi
  sleep 2
done
echo "[WARN] MySQL process exists but port 3306 is not ready yet." >&2
exit 1
