#!/bin/bash
DIR="$(cd "$(dirname "$0")" && pwd)"
PID="$DIR/server.pid"

if [ -f "$PID" ]; then
  kill "$(cat $PID)" 2>/dev/null && echo "World One 已停止" || echo "进程不存在"
  rm -f "$PID"
else
  pkill -f "worldone.jar" 2>/dev/null && echo "World One 已停止" || echo "未找到进程"
fi
