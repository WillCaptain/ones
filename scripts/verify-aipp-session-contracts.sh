#!/usr/bin/env bash
# 聚合运行与 app session / Layer3 session 扩展相关的 Maven 测试。
# 约定：本仓库与 entitir、shared 位于同一父目录下（例如 ~/Documents/code/github/）。
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GITHUB_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

run() {
  echo "==> $*"
  (cd "$1" && mvn -q test "${@:2}")
}

# 先安装协议库，其它模块测试依赖本地仓库中的最新 AippAppSpec
echo "==> install shared/aipp-protocol (with tests)"
(cd "$GITHUB_ROOT/shared/aipp-protocol" && mvn -q install)
run "$GITHUB_ROOT/entitir/world-entitir"
run "$GITHUB_ROOT/ones/memory-one"
run "$GITHUB_ROOT/ones/world-one"
echo "All session-related module tests passed."
