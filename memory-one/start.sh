#!/bin/bash
# memory-one 本地启动脚本
# 使用方式：
#   ./start.sh                           # 用 worldone 的 DB + LLM 环境变量
#   MEMORY_ONE_DB_URL=... ./start.sh     # 独立数据库
#   LLM_API_KEY=... ./start.sh           # 独立 LLM Key

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
JAR="$SCRIPT_DIR/target/memory-one-1.0-SNAPSHOT.jar"

if [ ! -f "$JAR" ]; then
  echo "[memory-one] JAR not found, building..."
  cd "$SCRIPT_DIR" && mvn package -DskipTests -q
fi

echo "[memory-one] Starting on port 8091..."
exec java \
  -Xmx256m \
  -jar "$JAR" \
  "$@"
