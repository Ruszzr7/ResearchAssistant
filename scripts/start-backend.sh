#!/usr/bin/env bash
# 启动后端（自动探测 JDK 17+）
set -e

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKEND_DIR="$(cd "$SCRIPT_DIR/../backend" && pwd)"

JDK_CANDIDATES=(
  "/c/tools/jdk-17.0.19+10"
  "/c/tools/jdk-17"
  "/c/tools/jdk-21"
  "/c/Program Files/Eclipse Adoptium/jdk-17.0.11+9-hotspot"
  "/c/Program Files/Java/jdk-17"
  "/c/Program Files/Java/jdk-21"
  "/c/Program Files (x86)/Java/jdk-17"
)

find_jdk() {
  # 1. 已有 JAVA_HOME
  if [ -n "$JAVA_HOME" ] && [ -f "$JAVA_HOME/bin/java.exe" ]; then
    echo "$JAVA_HOME"
    return
  fi

  # 2. PATH 中有 java
  if command -v java.exe &> /dev/null; then
    java_path=$(command -v java.exe)
    # 取 bin 的上级目录
    echo "$(cd "$(dirname "$java_path")/.." && pwd)"
    return
  fi

  # 3. 扫描候选路径
  for candidate in "${JDK_CANDIDATES[@]}"; do
    if [ -f "$candidate/bin/java.exe" ]; then
      echo "$candidate"
      return
    fi
  done

  # 4. 通配扫描
  for base in /c/tools "/c/Program Files/Eclipse Adoptium" "/c/Program Files/Java" "/c/Program Files (x86)/Java"; do
    if [ -d "$base" ]; then
      found=$(find "$base" -maxdepth 2 -name "java.exe" -path "*/bin/java.exe" -type f 2>/dev/null | head -n 1)
      if [ -n "$found" ]; then
        echo "$(cd "$(dirname "$found")/.." && pwd)"
        return
      fi
    fi
  done
}

JDK=$(find_jdk)

if [ -z "$JDK" ]; then
  echo -e "${RED}未找到 JDK 17+${NC}"
  echo "请安装 JDK 17 或更高版本，并设置 JAVA_HOME"
  echo "常见位置："
  echo "  - C:\\tools\\jdk-17.0.19+10"
  echo "  - C:\\Program Files\\Eclipse Adoptium\\jdk-17..."
  exit 1
fi

echo -e "${GREEN}找到 JDK: $JDK${NC}"

export JAVA_HOME="$JDK"
export PATH="$JAVA_HOME/bin:$PATH"

cd "$BACKEND_DIR"

# 检查后端是否已经在跑（先看 PID 文件，再看 8080 端口）
if [ -f "$BACKEND_DIR/backend.pid" ]; then
  pid=$(cat "$BACKEND_DIR/backend.pid" 2>/dev/null || true)
  if [ -n "$pid" ] && tasklist | grep -E "^\s*$pid\s" > /dev/null 2>&1; then
    echo -e "${YELLOW}后端已经在运行中 (PID: $pid)，无需重复启动${NC}"
    exit 0
  fi
fi

if netstat -ano | grep -q ":8080.*LISTENING"; then
  echo -e "${YELLOW}8080 端口已被占用，后端可能已经在运行${NC}"
  exit 0
fi

if ! command -v ./mvnw &> /dev/null; then
  echo -e "${RED}未找到 backend/mvnw，请确认在项目根目录下执行${NC}"
  exit 1
fi

echo "正在启动后端服务..."
nohup ./mvnw spring-boot:run -DskipTests > "$BACKEND_DIR/backend.log" 2>&1 &
echo $! > "$BACKEND_DIR/backend.pid"

echo -e "${GREEN}后端正在后台启动，PID: $(cat "$BACKEND_DIR/backend.pid")${NC}"
echo "日志文件: $BACKEND_DIR/backend.log"
echo "约 20-40 秒后可访问 http://localhost:8080"
