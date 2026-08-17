#!/bin/bash
set -e

PROJECT_ROOT=$(cd "$(dirname "$0")" && pwd)

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
# 1. 停止 PPTX 服务 (Docker)
# ==========================================
echo ""
echo ">>> [1/6] 停止 PPTX 服务 (Docker)..."
if [ "$DOCKER_AVAILABLE" = true ]; then
    if docker ps -q -f name=checkba-pptx-service 2>/dev/null | grep -q .; then
        echo "找到 PPTX 服务容器，正在停止..."
        docker stop checkba-pptx-service 2>/dev/null || true
        docker rm checkba-pptx-service 2>/dev/null || true
        sleep 1
        echo "✓ PPTX 服务已停止"
    else
        echo "未找到运行中的 PPTX 服务容器"
    fi
else
    echo "Docker 不可用，跳过..."
fi

# ==========================================
# 1.5 停止 MinerU 服务 (Docker)
# ==========================================
echo ""
echo ">>> [1.5/6] 停止 MinerU 服务 (Docker)..."
if [ "$DOCKER_AVAILABLE" = true ]; then
    if docker ps -q -f name=checkba-mineru-service 2>/dev/null | grep -q .; then
        echo "找到 MinerU 服务容器，正在停止..."
        docker stop checkba-mineru-service 2>/dev/null || true
        docker rm checkba-mineru-service 2>/dev/null || true
        sleep 1
        echo "✓ MinerU 服务已停止"
    else
        echo "未找到运行中的 MinerU 服务容器"
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
    echo "找到前端进程 (PID: $H5_PID)，正在停止..."
    kill -9 $H5_PID 2>/dev/null || true
    sleep 1
    echo "✓ 前端 H5 已停止"
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
    docker-compose up -d pptx-service
    
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
    docker-compose up -d mineru-service
    
    # 等待服务启动（MinerU 需要更长时间来加载模型）
    echo "⏳ 等待 MinerU 服务启动 (最多 300 秒，模型加载需要时间)..."
    echo "   提示: 首次启动需要下载模型，可能需要较长时间"
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
    export $(grep -v '^#' ./.env.production | xargs)
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

echo "✓ 桌面端:           已启动 (Electron)"
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
