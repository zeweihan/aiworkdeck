#!/bin/bash
set -e

PROJECT_ROOT=$(cd "$(dirname "$0")" && pwd)

# 端口号（9696/5173）是全局资源：多个 worktree 各自跑本脚本会抢同一个端口，
# lsof -ti:PORT 找到的 PID 未必是本 checkout 起的进程，可能是另一个 worktree 正在
# 用的、完全健康的后端/前端。杀之前用这个函数确认占用者的工作目录确实在本
# checkout 下，不是就不杀，只报警——避免"跑一下本脚本就把别人的开发环境弄挂了、
# 还看不出原因"（dev-board#74 审计条目）。lsof 拿不到 cwd（权限/平台差异）时
# 一并当"不是本 checkout"处理，拿不准就不杀。
pid_belongs_to_this_checkout() {
    local pid="$1"
    local cwd
    cwd=$(lsof -a -p "$pid" -d cwd -Fn 2>/dev/null | sed -n 's/^n//p')
    [ -n "$cwd" ] && [[ "$cwd" == "$PROJECT_ROOT"* ]]
}

echo "=================================================="
echo "   Checkba Project One-Click RESTART"
echo "=================================================="
echo "项目根目录: $PROJECT_ROOT"
echo ""

# ==========================================
# 0. 前置检查
# ==========================================
echo ">>> [检查] 验证必要依赖..."

# 检查 Docker 是否运行
if ! docker info > /dev/null 2>&1; then
    echo "⚠️ Docker 未运行！PPTX 服务需要 Docker。"
    echo "   请启动 Docker 后重试，或继续启动其他服务..."
    DOCKER_AVAILABLE=false
else
    echo "✓ Docker 运行正常"
    DOCKER_AVAILABLE=true
fi

# 检查 PostgreSQL 是否运行 (端口 5432)
if lsof -ti:5432 >/dev/null 2>&1; then
    echo "✓ PostgreSQL 运行正常 (端口 5432)"
else
    echo "⚠️ 警告：PostgreSQL 未运行 (端口 5432)，后端服务可能无法正常工作"
    echo "   请确保 PostgreSQL 已启动"
fi

# ==========================================
# 1. PPTX 服务 (Docker)
# ==========================================
echo ""
echo ">>> [1/6] 检查 PPTX 服务 (Docker)..."
if [ "$DOCKER_AVAILABLE" = true ]; then
    if docker ps -q -f name=checkba-pptx-service 2>/dev/null | grep -q .; then
        # 容器名在 docker-compose.yml 里是写死的全局名字，多个 worktree 共享同一个
        # 容器实例，不像端口那样能分辨"是不是本 checkout 起的"。以前这里无条件
        # stop+rm：随便哪个 worktree 跑一下本脚本就把这个可能正被别的会话用着的
        # 容器杀掉，而启动阶段又没有 --build，杀了重建也不会拿到新代码，纯粹是
        # 白白中断服务。改成留着不动——启动阶段的 docker-compose up -d 对配置没变
        # 的容器是幂等的，已经在跑就什么都不做；真要强制重建，手动 docker restart。
        echo "PPTX 服务容器已在运行，保留不动（多 worktree 共享同一实例，交给启动阶段的 up -d 幂等处理）"
    else
        echo "未找到运行中的 PPTX 服务容器，稍后启动阶段会创建"
    fi
else
    echo "Docker 不可用，跳过..."
fi

# ==========================================
# 1.5 MinerU 服务 (Docker)
# ==========================================
echo ""
echo ">>> [1.5/6] 检查 MinerU 服务 (Docker)..."
if [ "$DOCKER_AVAILABLE" = true ]; then
    if docker ps -q -f name=checkba-mineru-service 2>/dev/null | grep -q .; then
        # 理由同上面 PPTX：容器名全局共享，无条件 stop+rm 会杀掉别的 worktree/会话
        # 正用着的容器，且没有 --build 步骤，杀了重建也拿不到新代码。留着不动，
        # 交给启动阶段幂等的 docker-compose up -d。
        echo "MinerU 服务容器已在运行，保留不动（多 worktree 共享同一实例，交给启动阶段的 up -d 幂等处理）"
    else
        echo "未找到运行中的 MinerU 服务容器，稍后启动阶段会创建"
    fi
else
    echo "Docker 不可用，跳过..."
fi

# ==========================================
# 1.8 停止 EasyVoice 服务 (Docker) -> Skipped (语音合成走桌面捆绑的 Kokoro)
# ==========================================
# echo ""
# echo ">>> [1.8/6] 停止 EasyVoice 服务 (Docker)..."
# if [ "$DOCKER_AVAILABLE" = true ]; then
#     if docker ps -q -f name=checkba-easyvoice 2>/dev/null | grep -q .; then
#         echo "找到 EasyVoice 服务容器，正在停止..."
#         docker stop checkba-easyvoice 2>/dev/null || true
#         docker rm checkba-easyvoice 2>/dev/null || true
#         sleep 1
#         echo "✓ EasyVoice 服务已停止"
#     else
#         echo "未找到运行中的 EasyVoice 服务容器"
#     fi
# else
#     echo "Docker 不可用，跳过..."
# fi

# ==========================================
# 2. 停止后端 Java 服务 (端口 9696)
# ==========================================
echo ""
echo ">>> [2/6] 停止后端 Java 服务 (端口 9696)..."
BACKEND_PID=$(lsof -ti:9696 || true)
if [ -n "$BACKEND_PID" ]; then
    if pid_belongs_to_this_checkout "$BACKEND_PID"; then
        echo "找到后端进程 (PID: $BACKEND_PID)，正在停止..."
        kill $BACKEND_PID 2>/dev/null || true
        sleep 2
        # 如果进程还在，强制杀死
        if kill -0 $BACKEND_PID 2>/dev/null; then
            echo "进程仍在运行，强制杀死..."
            kill -9 $BACKEND_PID 2>/dev/null || true
            sleep 1
        fi
        echo "✓ 后端服务已停止"
    else
        echo "⚠️ 端口 9696 被其它工作目录的进程占用 (PID: $BACKEND_PID)，不是本 checkout 启动的，本脚本不会杀它。"
        echo "   多个 worktree 并行时端口是抢的：去那个 worktree 里停止，或者本目录先不启动后端。"
    fi
else
    echo "端口 9696 未被占用"
fi

# ==========================================
# 3. 停止桌面端 (Desktop)
# ==========================================
echo ""
echo ">>> [3/6] 停止桌面端..."
if [ -f "$PROJECT_ROOT/desktop/main/main.js" ]; then
    DESKTOP_PIDS=$(lsof -t "$PROJECT_ROOT/desktop/main/main.js" 2>/dev/null || true)
    if [ -n "$DESKTOP_PIDS" ]; then
        echo "找到桌面端进程 (PIDS: $DESKTOP_PIDS)，正在停止..."
        kill -9 $DESKTOP_PIDS 2>/dev/null || true
        sleep 1
        echo "✓ 桌面端已停止"
    else
        echo "未找到运行中的桌面端进程"
    fi
else
    echo "Warning: desktop/main/main.js not found, skipping desktop stop."
fi

# ==========================================
# 4. 停止前端 H5 (端口 5173)
# ==========================================
echo ""
echo ">>> [4/6] 停止前端 H5 (端口 5173)..."
H5_PID=$(lsof -ti:5173 || true)
if [ -n "$H5_PID" ]; then
    if pid_belongs_to_this_checkout "$H5_PID"; then
        echo "找到前端进程 (PID: $H5_PID)，正在停止..."
        kill -9 $H5_PID 2>/dev/null || true
        sleep 1
        echo "✓ 前端 H5 已停止"
    else
        echo "⚠️ 端口 5173 被其它工作目录的进程占用 (PID: $H5_PID)，不是本 checkout 启动的，本脚本不会杀它。"
        echo "   多个 worktree 并行时端口是抢的：去那个 worktree 里停止，或者本目录先不启动前端。"
    fi
else
    echo "端口 5173 未被占用"
fi

# ==========================================
# 5. 等待端口释放
# ==========================================
echo ""
echo ">>> [5/6] 等待 2 秒以确保端口释放..."
sleep 2

echo ""
echo "=================================================="
echo "   所有服务已停止，开始重新启动..."
echo "=================================================="

# ==========================================
# 启动服务
# ==========================================

# ==========================================
# 1. 启动 PPTX 服务 (Docker)
# ==========================================
echo ""
echo ">>> [启动 1/5] 启动 PPTX 服务 (Docker)..."
if [ "$DOCKER_AVAILABLE" = true ]; then
    cd "$PROJECT_ROOT"
    
    # 确保 .env 文件存在
    if [ ! -f pptx-service/.env ]; then
        echo "⚠️ pptx-service/.env 不存在，正在创建..."
        cat > pptx-service/.env << 'EOF'
# banana-slides 配置文件
AI_PROVIDER_FORMAT=google
GOOGLE_API_KEY=YOUR_GEMINI_API_KEY
GOOGLE_API_BASE=https://generativelanguage.googleapis.com/v1beta
PORT=5000
CORS_ORIGINS=*
LOG_LEVEL=INFO
OUTPUT_LANGUAGE=zh
IN_DOCKER=1
EOF
        echo "⚠️ 请编辑 pptx-service/.env 配置 GOOGLE_API_KEY"
    fi
    
    # 使用 docker-compose 启动 PPTX 服务
    echo "📦 启动 PPTX 服务容器..."
    # 脚本开头 set -e：这条命令失败（比如镜像没拉下来）以前会让整个脚本直接
    # exit，后端/H5/桌面端三步全被跳过，且这发生在"所有服务已停止，开始重新
    # 启动..."已经打印之后，看起来像卡住而不是失败。加 if 包一层，失败就如实
    # 提示并继续启动其余服务。
    if ! docker-compose up -d pptx-service; then
        echo "PPTX 服务容器启动命令失败（docker-compose up 非零退出），跳过 PPTX，继续启动其余服务..."
    fi

    # 等待服务启动
    echo "⏳ 等待 PPTX 服务启动 (最多 30 秒)..."
    for i in {1..30}; do
        if curl -s http://localhost:5001/health > /dev/null 2>&1; then
            echo "✓ PPTX 服务已启动: http://localhost:5001"
            break
        fi
        sleep 1
    done
    
    # 检查是否启动成功
    if ! curl -s http://localhost:5001/health > /dev/null 2>&1; then
        echo "⚠️ PPTX 服务启动超时，请检查 Docker 日志:"
        echo "   docker-compose logs pptx-service"
    fi
else
    echo "Docker 不可用，跳过 PPTX 服务启动..."
fi

# ==========================================
# 1.5 启动 MinerU 服务 (Docker)
# ==========================================
echo ""
echo ">>> [启动 1.5/5] 启动 MinerU 服务 (Docker)..."
if [ "$DOCKER_AVAILABLE" = true ]; then
    cd "$PROJECT_ROOT"
    
    # 使用 docker-compose 启动 MinerU 服务
    echo "📦 启动 MinerU 服务容器..."
    # 同上面 PPTX 那条：set -e 下裸命令失败会让整个脚本直接 exit，后端/H5/
    # 桌面端三步全被跳过。加 if 包一层，失败就如实提示并继续。
    if ! docker-compose up -d mineru-service; then
        echo "MinerU 服务容器启动命令失败（docker-compose up 非零退出），跳过 MinerU，继续启动其余服务..."
    fi

    # 等待服务启动（MinerU 需要更长时间来加载模型）
    echo "⏳ 等待 MinerU 服务启动 (最多 300 秒，模型加载需要时间)..."
    echo "   提示: 首次启动需要下载模型，可能需要较长时间"
    MINERU_TIMED_OUT=false
    for i in {1..300}; do
        if curl -s http://localhost:8001/docs > /dev/null 2>&1; then
            echo "✓ MinerU 服务已启动: http://localhost:8001"
            break
        fi
        # 每30秒打印一次进度
        if [ $((i % 30)) -eq 0 ]; then
            echo "   已等待 ${i} 秒..."
        fi
        sleep 1
    done
    
    # 检查是否启动成功
    if ! curl -s http://localhost:8001/docs > /dev/null 2>&1; then
        echo "⚠️ MinerU 服务启动超时，请检查 Docker 日志:"
        echo "   docker-compose logs mineru-service"
        # 已经在这里如实打过"启动超时"了，把这个事实带到最后的汇总块，不然
        # 汇总块自己重新 curl 一次照样连不上，只会打"⏳ 启动中..."——300秒都等
        # 完了还说"启动中"，是误报，不是"还在起"。
        MINERU_TIMED_OUT=true
    fi
else
    echo "Docker 不可用，跳过 MinerU 服务启动..."
fi

# ==========================================
# 1.8 启动 EasyVoice 服务 (Docker) -> Skipped (语音合成走桌面捆绑的 Kokoro)
# ==========================================
# echo ""
# echo ">>> [启动 1.8/5] 启动 EasyVoice 服务 (Docker)..."
# if [ "$DOCKER_AVAILABLE" = true ]; then
#     cd "$PROJECT_ROOT"
#     
#     # 使用 docker-compose 启动 EasyVoice 服务
#     echo "📦 启动 EasyVoice 服务容器..."
#     docker-compose up -d easyvoice
#     
#     # 等待服务启动
#     echo "⏳ 等待 EasyVoice 服务启动 (最多 30 秒)..."
#     for i in {1..30}; do
#         if curl -s http://localhost:9549/api/health > /dev/null 2>&1; then
#             echo "✓ EasyVoice 服务已启动: http://localhost:9549"
#             break
#         fi
#         sleep 1
#     done
#     
#     # 检查是否启动成功 (EasyVoice app.ts mounts /api/health)
#     if ! curl -s http://localhost:9549/api/health > /dev/null 2>&1; then
#          echo "⚠️ EasyVoice 服务启动可能超时或健康检查失败，请检查 Docker 日志:"
#          echo "   docker-compose logs easyvoice"
#     fi
# else
#     echo "Docker 不可用，跳过 EasyVoice 服务启动..."
# fi


# ==========================================
# 1.8 检查 TTS 服务依赖
# ==========================================
# ==========================================
# 1.9 检查 TTS 服务依赖 (Deprecated by EasyVoice Docker)
# ==========================================
echo ""
echo ">>> [启动 1.9/5] 检查 TTS 服务依赖 (Legacy)..."
# if command -v edge-tts &> /dev/null; then
#     echo "✓ TTS 服务依赖 (edge-tts) 已安装"
#     edge-tts --version
# else
#     echo "⚠️ TTS 服务依赖 (edge-tts) 未找到，但已切换到 EasyVoice Docker，忽略..."
# fi

# ==========================================
# 2. 启动后端 Java 服务
# ==========================================
echo ""
echo ">>> [启动 2/5] 启动后端 Java 服务..."
cd "$PROJECT_ROOT/backend"

# 调用现有的 restart-backend.sh
# Load environment variables from backend/.env.production if it exists
if [ -f "./.env.production" ]; then
    echo "Found backend/.env.production, loading environment variables..."
    # 原来的 export $(grep -v '^#' ... | xargs) 没加引号，值里带空格（比如带空格的
    # 路径/显示名）会被词分割截断到第一个空格为止，且没有任何报错——静默丢数据。
    # 直接当 shell 源码 source，交给 shell 自己按引号语义解析，全程不报错也不截断。
    set -a
    . ./.env.production
    set +a
fi

if [ -f "./restart-backend.sh" ]; then
    chmod +x ./restart-backend.sh
    ./restart-backend.sh
else
    echo "Error: backend/restart-backend.sh not found!"
    exit 1
fi

cd "$PROJECT_ROOT"

# ==========================================
# 3. 启动前端 H5
# ==========================================
echo ""
echo ">>> [启动 3/5] 启动前端 H5..."

# 检查端口 5173 是否被占用
if lsof -ti:5173 >/dev/null 2>&1; then
    echo "前端 H5 端口 5173 已被占用，跳过启动"
else
    cd "$PROJECT_ROOT/frontend"
    echo "执行启动命令: nohup npm run dev:h5 > h5.log 2>&1 &"
    nohup npm run dev:h5 > h5.log 2>&1 &
    
    echo "等待前端 H5 启动 (检查端口 5173)..."
    for i in {1..30}; do
        if lsof -ti:5173 >/dev/null 2>&1; then
            echo "✓ 前端 H5 启动成功！"
            break
        fi
        sleep 1
    done
    
    # 二次确认
    if ! lsof -ti:5173 >/dev/null 2>&1; then
        echo "⚠️ 警告：30秒内未检测到端口 5173，请检查 frontend/h5.log"
    fi
    cd "$PROJECT_ROOT"
fi

# 增加延迟，确保前端资源加载
sleep 2

# ==========================================
# 4. 启动桌面端
# ==========================================
echo ""
echo ">>> [启动 4/5] 启动桌面端 (Desktop)..."
cd "$PROJECT_ROOT/desktop"

echo "执行启动命令: nohup npm run dev > desktop.log 2>&1 &"
nohup npm run dev > desktop.log 2>&1 &

# 同后端/H5/PPTX/MinerU 一样做启动复检——单靠 nohup ... & 立刻返回不代表 Electron
# 真的起来了（npm/electron 可能马上崩溃退出），下面汇总块原来对这一步是无条件打
# "已启动"，跟其余几项能打失败的做法不一致。探测手法与脚本 STOP 段落停止桌面端
# 时用的是同一个（lsof -t 该文件路径，看有没有进程打开着 main.js）。
echo "等待桌面端启动 (检查 Electron 主进程)..."
for i in {1..30}; do
    if lsof -t "$PROJECT_ROOT/desktop/main/main.js" >/dev/null 2>&1; then
        echo "桌面端启动成功！"
        break
    fi
    sleep 1
done

# 二次确认
if ! lsof -t "$PROJECT_ROOT/desktop/main/main.js" >/dev/null 2>&1; then
    echo "警告：30秒内未检测到桌面端进程，请检查 desktop/desktop.log"
fi

cd "$PROJECT_ROOT"

# ==========================================
# 输出汇总
# ==========================================
echo ""
echo "=================================================="
echo "   🎉 所有服务重启完毕！"
echo "=================================================="
echo ""
echo "服务状态汇总:"
echo "────────────────────────────────────────────────────"

# 检查各服务状态
if lsof -ti:9696 >/dev/null 2>&1; then
    echo "✓ 后端 Java 服务:   http://localhost:9696"
else
    echo "✗ 后端 Java 服务:   未启动 (端口 9696)"
fi

if lsof -ti:5173 >/dev/null 2>&1; then
    echo "✓ 前端 H5:          http://localhost:5173"
else
    echo "✗ 前端 H5:          未启动 (端口 5173)"
fi

if [ "$DOCKER_AVAILABLE" = true ]; then
    if curl -s http://localhost:5001/health > /dev/null 2>&1; then
        echo "✓ PPTX 服务:        http://localhost:5001"
    else
        echo "✗ PPTX 服务:        未启动 (端口 5001)"
    fi
    
    if curl -s http://localhost:8001/docs > /dev/null 2>&1; then
        echo "✓ MinerU 服务:      http://localhost:8001"
    elif [ "$MINERU_TIMED_OUT" = true ]; then
        # 上面等待阶段已经把 300 秒都等完还是连不上——是超时，不是"还在起"
        echo "MinerU 服务:        启动超时（300 秒），请检查: docker-compose logs mineru-service"
    else
        echo "⏳ MinerU 服务:      启动中... (端口 8001，模型加载需要时间)"
    fi
    
    # if curl -s http://localhost:9549/api/health > /dev/null 2>&1; then
    #     echo "✓ EasyVoice 服务:   http://localhost:9549"
    # else
    #     echo "✗ EasyVoice 服务:   未启动 (端口 9549)"
    # fi
    echo "- TTS 服务:         本机 Kokoro（桌面包内置，dev 态需单独起）"
else
    echo "- MinerU 服务:      Docker 不可用"
fi

if command -v edge-tts &> /dev/null; then
    echo "✓ TTS 服务依赖:     已安装 (edge-tts)"
else
    echo "✗ TTS 服务依赖:     未安装 (edge-tts)"
fi

if lsof -t "$PROJECT_ROOT/desktop/main/main.js" >/dev/null 2>&1; then
    echo "✓ 桌面端:           已启动 (Electron)"
else
    echo "桌面端:             未启动，请检查 desktop/desktop.log"
fi
echo "────────────────────────────────────────────────────"
echo ""
echo "日志文件位置:"
echo "  后端日志: backend/app.log"
echo "  前端日志: frontend/h5.log"
echo "  桌面日志: desktop/desktop.log"
if [ "$DOCKER_AVAILABLE" = true ]; then
    echo "  PPTX 日志: docker-compose logs pptx-service"
    echo "  MinerU 日志: docker-compose logs mineru-service"
fi
echo ""
