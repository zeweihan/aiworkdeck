#!/bin/bash
set -e

# 确保脚本在 backend 目录下执行，解决从根目录即使 invoked 找不到 pom.xml 的问题
cd "$(dirname "$0")"
BACKEND_DIR=$(pwd)

echo "== 1. 打包 =="
mvn clean package -DskipTests

echo "== 2. 停止旧进程（端口 9696） =="
PID=$(lsof -ti:9696 || true)
if [ -n "$PID" ]; then
  # 端口 9696 是全局资源，多个 worktree 并行开发时可能是另一个 checkout 的、完全
  # 健康的后端占着——不是本脚本起的进程就不杀，只报警（dev-board#74 审计条目：
  # restart-all.sh 同一问题，这里独立调用时也要有同样的防护，否则 restart-all.sh
  # 里加的保护会在这一步被绕开重新杀掉）。lsof 拿不到 cwd 时按"不是本 checkout"处理。
  PID_CWD=$(lsof -a -p "$PID" -d cwd -Fn 2>/dev/null | sed -n 's/^n//p')
  if [ "$PID_CWD" != "$BACKEND_DIR" ]; then
    echo "⚠️ 端口 9696 被其它工作目录的进程占用 (PID: $PID, 工作目录: ${PID_CWD:-未知})，不是本 checkout 启动的，不会杀它。"
    echo "   新 Jar 无法绑定到 9696，下面的启动步骤会因端口占用而失败——先去占用端口的那个 worktree 里停止。"
  else
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