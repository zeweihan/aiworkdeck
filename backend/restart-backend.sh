#!/bin/bash
set -e

# 确保脚本在 backend 目录下执行，解决从根目录即使 invoked 找不到 pom.xml 的问题
cd "$(dirname "$0")"

# Load environment variables from .env if it exists
if [ -f "./.env" ]; then
    echo "Found .env, loading environment variables..."
    export $(grep -v '^#' ./.env | xargs)
fi

# Load environment variables from .env.production if it exists (overrides .env)
if [ -f "./.env.production" ]; then
    echo "Found .env.production, loading environment variables..."
    export $(grep -v '^#' ./.env.production | xargs)
fi

echo "== 1. 打包 =="
mvn clean package -DskipTests

echo "== 2. 停止旧进程（端口 9696） =="
PID=$(lsof -ti:9696 || true)
if [ -n "$PID" ]; then
  echo "找到进程 PID=${PID}，kill..."
  # 使用 kill -0 检查进程是否存在，避免 kill 失败导致脚本退出
  if kill -0 $PID 2>/dev/null; then
    kill $PID || true
    sleep 2
    # 如果进程还在，强制杀死
    if kill -0 $PID 2>/dev/null; then
      echo "进程仍在运行，强制杀死..."
      kill -9 $PID || true
      sleep 2
    fi
  fi
else
  echo "端口 9696 没有正在运行的进程"
fi

echo "== 3. 启动新 Jar（prod 配置） =="
JAR=$(ls target | grep '\.jar$' | head -n 1)
if [ -z "$JAR" ]; then
  echo "ERROR: target 目录下没有找到 jar，请检查构建是否成功"
  exit 1
fi

echo "启动命令: java -jar target/$JAR"
nohup java -jar "target/$JAR" > app.log 2>&1 &
NEW_PID=$!

echo "新进程已启动，PID=$NEW_PID"

# 等待进程启动，最多等待15秒
echo "等待服务启动..."
for i in {1..15}; do
  if ! kill -0 $NEW_PID 2>/dev/null; then
    echo "✗ 错误：进程 $NEW_PID 启动失败，请查看 app.log 获取错误信息"
    echo "最后 20 行日志："
    tail -20 app.log
    exit 1
  fi
  
  # 检查端口是否被监听
  if lsof -ti:9696 >/dev/null 2>&1; then
    echo "✓ 进程 $NEW_PID 正在运行"
    echo "✓ 端口 9696 已被监听"
    echo "启动成功！"
    exit 0
  fi
  
  sleep 1
done

# 如果15秒后端口仍未监听，检查进程状态
if kill -0 $NEW_PID 2>/dev/null; then
  echo "✓ 进程 $NEW_PID 正在运行"
  echo "⚠ 警告：进程在运行，但端口 9696 在15秒内未被监听，请检查日志 app.log"
  echo "最后 30 行日志："
  tail -30 app.log
else
  echo "✗ 错误：进程 $NEW_PID 启动失败，请查看 app.log 获取错误信息"
  echo "最后 30 行日志："
  tail -30 app.log
  exit 1
fi