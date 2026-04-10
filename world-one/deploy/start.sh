#!/bin/bash
# start.sh — 启动 World One 服务
# 用法：./start.sh [--daemon] [--with-world-entitir]
#   --daemon               后台运行
#   --with-world-entitir   同时启动 world.entitir 并自动注册到 Registry
set -e

DAEMON=false
WITH_APP=false
for arg in "$@"; do
  [[ "$arg" == "--daemon" ]]              && DAEMON=true
  [[ "$arg" == "--with-world-entitir" ]]  && WITH_APP=true
done

DIR="$(cd "$(dirname "$0")" && pwd)"
JAR="$DIR/worldone.jar"
LOG="$DIR/server.log"
PID="$DIR/server.pid"
W1_PORT="${WORLDONE_PORT:-8090}"

if [ ! -f "$JAR" ]; then
  echo "错误：$JAR 不存在，请先运行 install.sh"
  exit 1
fi

# ── 可选：先启动 world.entitir ──────────────────────────────────────────────
WAPP_DIR="$(dirname "$DIR")/world-entitir"
if $WITH_APP && [ -f "$WAPP_DIR/deploy/world-entitir.jar" ]; then
  echo "启动 world.entitir..."
  "$WAPP_DIR/deploy/start.sh"
  sleep 2
fi

# ── 启动 World One ─────────────────────────────────────────────────────────
JAVA_ARGS=(
  --server.port="$W1_PORT"
)

if $DAEMON; then
  nohup java -jar "$JAR" "${JAVA_ARGS[@]}" >> "$LOG" 2>&1 &
  echo $! > "$PID"
  echo "World One 已启动（daemon），PID=$(cat $PID)"
  echo "日志：tail -f $LOG"
else
  java -jar "$JAR" "${JAVA_ARGS[@]}" &
  echo $! > "$PID"
fi

# ── 等待 World One 就绪，自动注册 world.entitir ─────────────────────────────
if $WITH_APP; then
  echo "等待 World One 就绪..."
  for i in $(seq 1 15); do
    if curl -s "http://localhost:$W1_PORT/api/registry" > /dev/null 2>&1; then
      WAPP_PORT="${WORLD_ENTITIR_PORT:-8093}"
      echo "注册 world.entitir 到 Registry..."
      curl -s -X POST "http://localhost:$W1_PORT/api/registry/install" \
        -H "Content-Type: application/json" \
        -d "{\"app_id\":\"world\",\"base_url\":\"http://localhost:$WAPP_PORT\"}" \
        | python3 -c "import sys,json; d=json.load(sys.stdin); print('Registry:', d.get('message', d))" \
        2>/dev/null || true
      break
    fi
    sleep 1
  done
fi

if ! $DAEMON; then
  wait
fi
