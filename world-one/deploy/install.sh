#!/bin/bash
# install.sh — 构建 worldone fat JAR，准备部署产物
set -e
cd "$(dirname "$0")/.."

echo "=== [1/3] 安装依赖（ontology + aip）==="
cd ../ontology && mvn install -q -DskipTests
cd ../aip      && mvn install -q -DskipTests
cd ../worldone

echo "=== [2/3] 打包 worldone ==="
mvn clean package -q -DskipTests

echo "=== [3/3] 复制产物到 deploy/ ==="
cp target/worldone-1.0-SNAPSHOT.jar deploy/worldone.jar
echo "完成：deploy/worldone.jar"
