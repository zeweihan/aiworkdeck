"""
Kokoro 本地 TTS 服务：OpenAI 兼容的极薄包装层（桌面版语音合成，数据不出本机）。

端点：
  GET  /health            —— 存活检查（不依赖模型，CI 冒烟用）
  GET  /v1/audio/voices   —— 可用音色列表（固定表，与 Kokoro-82M-v1.1-zh 仓库对应）
  POST /v1/audio/speech   —— OpenAI 兼容合成：{input, voice, speed} → WAV

设计约束（见 docs/DESKTOP_LOCAL_BUNDLE_PLAN.md Phase 3）：
  - 模型懒加载：首次合成才初始化 KPipeline，/health 恒可用；
  - 运行时零出网：桌面侧注入 HF_HUB_OFFLINE=1，模型由 ModelManager 预下载到 HF_HOME；
  - 按 voice 前缀选 pipeline：zf_/zm_ → 中文（lang_code='z'），af_/bf_ → 英文（'a'）。
"""
import io
import os
import logging

import numpy as np
import soundfile as sf
from fastapi import FastAPI, HTTPException
from fastapi.responses import Response
from pydantic import BaseModel

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(name)s - %(message)s")
logger = logging.getLogger("kokoro-service")

REPO_ID = os.getenv("KOKORO_REPO_ID", "hexgrad/Kokoro-82M-v1.1-zh")
SAMPLE_RATE = 24000

# 音色表：与 v1.1-zh 仓库自带 voices 对应（Task 1 实测校准）
VOICES = [
    {"voiceId": "zf_001", "name": "晓晓", "gender": "female", "locale": "zh-CN"},
    {"voiceId": "zm_010", "name": "云山", "gender": "male", "locale": "zh-CN"},
    {"voiceId": "af_maple", "name": "Maple", "gender": "female", "locale": "en-US"},
    {"voiceId": "bf_vale", "name": "Vale", "gender": "female", "locale": "en-GB"},
]
DEFAULT_VOICE = "zf_001"

app = FastAPI(title="Kokoro Local TTS", version="1.0.0")

_pipelines = {}


def _pipeline(lang_code: str):
    """按语言缓存 KPipeline（模型懒加载，进程内单例）"""
    if lang_code not in _pipelines:
        from kokoro import KPipeline  # 延迟 import：/health 不碰 torch
        logger.info("Initializing KPipeline lang=%s repo=%s", lang_code, REPO_ID)
        _pipelines[lang_code] = KPipeline(lang_code=lang_code, repo_id=REPO_ID)
    return _pipelines[lang_code]


def _lang_for_voice(voice: str) -> str:
    return "z" if voice.startswith(("zf_", "zm_")) else "a"


class SpeechRequest(BaseModel):
    input: str
    voice: str = DEFAULT_VOICE
    speed: float = 1.0
    model: str = "kokoro"          # OpenAI 兼容字段，忽略
    response_format: str = "wav"   # 仅支持 wav


@app.get("/health")
def health():
    return {"status": "ok", "service": "kokoro-service", "repo": REPO_ID}


@app.get("/v1/audio/voices")
def voices():
    return {"voices": VOICES}


@app.post("/v1/audio/speech")
def speech(req: SpeechRequest):
    text = (req.input or "").strip()
    if not text:
        raise HTTPException(status_code=400, detail="input is required")
    voice = req.voice if any(v["voiceId"] == req.voice for v in VOICES) else DEFAULT_VOICE
    speed = min(max(req.speed or 1.0, 0.5), 2.0)

    try:
        pipeline = _pipeline(_lang_for_voice(voice))
        chunks = []
        for _, _, audio in pipeline(text, voice=voice, speed=speed):
            if audio is not None:
                chunks.append(audio)
        if not chunks:
            raise RuntimeError("no audio generated")
        wav = np.concatenate(chunks)
        buf = io.BytesIO()
        sf.write(buf, wav, SAMPLE_RATE, format="WAV")
        return Response(content=buf.getvalue(), media_type="audio/wav")
    except HTTPException:
        raise
    except Exception as e:  # noqa: BLE001 —— 统一转 500，细节进日志
        logger.exception("synthesis failed")
        raise HTTPException(status_code=500, detail=f"synthesis failed: {e}")


if __name__ == "__main__":
    import uvicorn
    port = int(os.getenv("PORT", "8880"))
    logger.info("Kokoro Local TTS starting on 127.0.0.1:%s (repo=%s)", port, REPO_ID)
    uvicorn.run(app, host="127.0.0.1", port=port, log_level="info")
