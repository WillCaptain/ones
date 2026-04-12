#!/bin/bash
# start.sh — 启动 World One 服务（+ 依赖 app 自动注册）
# 用法：./start.sh [--daemon] [--with-world-entitir] [--with-memory-one]
#   --daemon               后台运行
#   --with-world-entitir   同时启动 world.entitir 并自动注册到 Registry
#   --with-memory-one      同时启动 memory-one 并自动注册到 Registry（推荐）
set -e

DAEMON=false
WITH_APP=false
WITH_MEMORY=false
for arg in "$@"; do
  [[ "$arg" == "--daemon" ]]              && DAEMON=true
  [[ "$arg" == "--with-world-entitir" ]]  && WITH_APP=true
  [[ "$arg" == "--with-memory-one" ]]     && WITH_MEMORY=true
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

# ── 可选：先启动 memory-one ─────────────────────────────────────────────────
MEMORY_DIR="$(dirname "$DIR")/../ones/memory-one"
# 兼容两种路径（部署在 ones/ 下或同级目录）
[ -f "$MEMORY_DIR/start.sh" ] || MEMORY_DIR="$(dirname "$DIR")/memory-one"
if $WITH_MEMORY && [ -f "$MEMORY_DIR/start.sh" ]; then
  echo "启动 memory-one..."
  cd "$MEMORY_DIR" && nohup ./start.sh >> /tmp/memory-one.log 2>&1 &
  echo "memory-one pid=$!"
  cd "$DIR"
  sleep 3
elif $WITH_MEMORY; then
  echo "警告：未找到 memory-one/start.sh，跳过"
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

# ── 等待 World One 就绪，自动注册所有 app ───────────────────────────────────
wait_for_ready() {
  echo "等待 World One 就绪（http://localhost:$W1_PORT）..."
  for i in $(seq 1 20); do
    if curl -sf "http://localhost:$W1_PORT/api/registry" > /dev/null 2>&1; then
      echo "World One 已就绪"
      return 0
    fi
    sleep 1
  done
  echo "警告：World One 20s 内未就绪，跳过 app 注册"
  return 1
}

register_app() {
  local app_id=$1 base_url=$2 port=$3
  # 等待 app 就绪
  for i in $(seq 1 10); do
    if curl -sf "${base_url}/api/skills" > /dev/null 2>&1; then
      break
    fi
    sleep 1
  done
  curl -s -X POST "http://localhost:$W1_PORT/api/registry/install" \
    -H "Content-Type: application/json" \
    -d "{\"app_id\":\"${app_id}\",\"base_url\":\"${base_url}\"}" \
    | python3 -c "import sys,json; d=json.load(sys.stdin); print('Registry [${app_id}]:', d.get('message', d))" \
    2>/dev/null || echo "注册 ${app_id} 失败"
}

if $WITH_APP || $WITH_MEMORY; then
  wait_for_ready || true

  if $WITH_APP; then
    WAPP_PORT="${WORLD_ENTITIR_PORT:-8093}"
    echo "注册 world.entitir..."
    register_app "world" "http://localhost:$WAPP_PORT" "$WAPP_PORT"
  fi

  if $WITH_MEMORY; then
    MEMORY_PORT="${MEMORY_ONE_PORT:-8091}"
    echo "注册 memory-one..."
    register_app "memory-one" "http://localhost:$MEMORY_PORT" "$MEMORY_PORT"
  fi
fi

if ! $DAEMON; then
  wait
fi
