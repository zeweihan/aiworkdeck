# MinerU API Service Dockerfile
# Based on official MinerU documentation
# Uses CPU pipeline backend for document parsing

FROM python:3.10-slim

# Set environment variables
ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1 \
    # Use pipeline backend for CPU mode (no GPU required)
    MINERU_DEVICE_MODE=cpu \
    MINERU_MODEL_SOURCE=modelscope

# Install system dependencies (based on official requirements)
RUN apt-get update && apt-get install -y --no-install-recommends \
    fonts-noto-core \
    fonts-noto-cjk \
    fontconfig \
    libgl1 \
    libglib2.0-0 \
    curl \
    && fc-cache -fv \
    && apt-get clean \
    && rm -rf /var/lib/apt/lists/*

# Create app directory
WORKDIR /app

# Create directories for uploads and outputs
RUN mkdir -p /app/uploads /app/outputs

# Install MinerU with core dependencies (official method)
# Using mineru[core] which includes the API server
# Note: PIP_NO_CACHE_DIR=1 is set, so no cache purge needed
# MinerU 3.x 升级：合并前请在真机跑 compat_smoke_test.sh 验证（见 README）
RUN pip install --upgrade pip && \
    pip install -U "mineru[core]>=3.4,<4" -i https://mirrors.aliyun.com/pypi/simple

# Download models for pipeline backend (required for offline use)
# This will download models from modelscope (faster for China users)
RUN mineru-models-download -s modelscope -m pipeline

# Expose port for mineru-api
EXPOSE 8000

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=120s --retries=3 \
    CMD curl -f http://localhost:8000/docs || exit 1

# Run the official MinerU API server
# Using pipeline backend for CPU mode
CMD ["mineru-api", "--host", "0.0.0.0", "--port", "8000"]
