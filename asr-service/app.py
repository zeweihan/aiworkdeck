"""
本地 ASR 服务：OpenAI 兼容的极薄包装层（会议录音转写，音频不出本机）。

端点：
  GET  /health                   —— 存活 + 模型在不在（不加载模型，CI 冒烟与就绪探测共用）
  POST /v1/audio/transcriptions  —— OpenAI 兼容转写：multipart file → {text, segments}

设计约束（与 kokoro-service 同一套，见设计文档 §6.2）：
  - 模型懒加载：首次转写才初始化 WhisperModel，/health 恒可用；
  - 运行时零出网：桌面侧注入 HF_HUB_OFFLINE=1，模型由 ModelManager 预下载到 HF_HOME；
  - 引擎选 faster-whisper（CTranslate2 后端）：纯 Python wheel，与另外三个 pysvc 服务
    同一条打包路径；whisper.cpp 要现编原生二进制，那条路 macOS 还得逐个签名+公证。

**本地档没有说话人分离。** faster-whisper 不提供，而 pyannote 要 HF token + 许可协议 +
额外几百 MB 模型，与「零配置」直接冲突。所有段落一律 speaker="1"，
由界面明确写出这一取舍——不能让用户以为本地档与云端听悟等价。
"""
import logging
import os
import tempfile
import threading

from fastapi import FastAPI, File, Form, HTTPException, UploadFile

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(name)s - %(message)s")
logger = logging.getLogger("asr-service")

# medium 是「中文可用」的最低档：small 对法律术语与人名的错字率明显更高，
# 而 large-v3 在纯 CPU 上慢到一场两小时的会要跑几个小时。可用环境变量下调。
MODEL = os.getenv("ASR_MODEL", "Systran/faster-whisper-medium")
DEVICE = os.getenv("ASR_DEVICE", "cpu")
# int8 量化：CPU 上比 float32 快数倍、内存占用约三分之一，词错率的差异在会议录音上听不出来
COMPUTE_TYPE = os.getenv("ASR_COMPUTE_TYPE", "int8")
BEAM_SIZE = int(os.getenv("ASR_BEAM_SIZE", "5"))

# Whisper 说普通话时**稳定输出繁体**（本机实测：两分钟的普通话会见录音整篇繁体，
# 内容几乎全对）。对大陆律师来说这是一份要整篇重排的笔录，不是小瑕疵。
# 试过社区常用的 initial_prompt 偏置（"以下是普通话的句子…"），一个字都没纠正过来——
# Whisper 的 prompt 是"上文示例"不是"指令"，对书写体的影响不可靠。
# 所以改成确定性的后处理：OpenCC 繁转简（纯 Python，1.1MB，不出网）。
# 想保留繁体的用户把 ASR_OUTPUT_SCRIPT 设成 original。
OUTPUT_SCRIPT = os.getenv("ASR_OUTPUT_SCRIPT", "simplified")

app = FastAPI(title="AI Workdeck Local ASR", version="1.0.0")

_model = None
_converter = None
# CTranslate2 的一个模型实例同时只跑一路转写。桌面端本来也不会并发转两场会，
# 与其让两路互相拖慢到都不可用，不如老实排队。
_lock = threading.Lock()


def _to_simplified(text: str) -> str:
    """繁转简。转换器懒加载（/health 不碰它），失败时原样返回——宁可繁体也不能丢转写稿。"""
    global _converter
    if OUTPUT_SCRIPT != "simplified" or not text:
        return text
    try:
        if _converter is None:
            import opencc
            _converter = opencc.OpenCC("t2s")
        return _converter.convert(text)
    except Exception:  # noqa: BLE001
        logger.warning("繁转简失败，按原文返回", exc_info=True)
        return text


def _model_present() -> bool:
    """模型文件在不在。只看磁盘：不加载模型、不出网，所以 /health 可以随便调。"""
    if os.path.isdir(MODEL):  # ASR_MODEL 指向本地目录时直接看那个目录
        return os.path.isfile(os.path.join(MODEL, "model.bin"))
    hf_home = os.getenv("HF_HOME") or os.path.expanduser("~/.cache/huggingface")
    snapshots = os.path.join(hf_home, "hub", "models--" + MODEL.replace("/", "--"), "snapshots")
    if not os.path.isdir(snapshots):
        return False
    # isfile 会跟随符号链接：HF 缓存里 snapshots 下全是指向 blobs 的软链，
    # 下载被打断留下的断链在这里自然算「不在」。
    return any(
        os.path.isfile(os.path.join(snapshots, rev, "model.bin"))
        for rev in os.listdir(snapshots)
    )


def _load():
    """进程内单例。首次转写才 import faster_whisper——/health 不碰 ctranslate2。"""
    global _model
    if _model is None:
        from faster_whisper import WhisperModel
        logger.info("Loading WhisperModel model=%s device=%s compute=%s", MODEL, DEVICE, COMPUTE_TYPE)
        _model = WhisperModel(MODEL, device=DEVICE, compute_type=COMPUTE_TYPE)
    return _model


@app.get("/health")
def health():
    return {
        "status": "ok",
        "service": "asr-service",
        "model": MODEL,
        "modelReady": _model_present(),
        # 说话人分离是两档之间最大的能力差，写进 /health 让上层不必把它硬编码一遍
        "diarization": False,
    }


@app.post("/v1/audio/transcriptions")
def transcriptions(
    file: UploadFile = File(...),
    model: str = Form(default=""),            # OpenAI 兼容字段，忽略（模型由本服务的配置决定）
    language: str = Form(default=""),         # 留空 = 自动识别
    prompt: str = Form(default=""),           # OpenAI 的 initial_prompt，可喂当事人名/术语提高准确率
    response_format: str = Form(default="json"),
    temperature: float = Form(default=0.0),
):
    if not _model_present():
        # 409 而不是 500：这不是故障，是「组件还没装」，上层据此引导下载而不是报错重试
        raise HTTPException(status_code=409, detail=f"model not downloaded: {MODEL}")

    suffix = os.path.splitext(file.filename or "")[1] or ".audio"
    tmp = tempfile.NamedTemporaryFile(suffix=suffix, delete=False)
    try:
        # 一场两小时的会几百 MB，分块落盘而不是整份读进内存
        while True:
            chunk = file.file.read(1024 * 1024)
            if not chunk:
                break
            tmp.write(chunk)
        tmp.close()

        with _lock:
            segments, info = _load().transcribe(
                tmp.name,
                language=language.strip() or None,
                initial_prompt=prompt.strip() or None,
                beam_size=BEAM_SIZE,
                temperature=temperature,
                # 会议录音里大段静音很常见，VAD 一开能省掉可观的算力，
                # 也避免 whisper 在静音段上产生幻觉重复。模型随 wheel 自带，不额外出网。
                vad_filter=True,
            )
            # segments 是生成器，转写在这一步才真正发生
            items = [
                {"id": i, "start": round(s.start, 3), "end": round(s.end, 3),
                 "text": _to_simplified(s.text.strip())}
                for i, s in enumerate(segments)
                if s.text and s.text.strip()
            ]
    except HTTPException:
        raise
    except Exception as e:  # noqa: BLE001 —— 统一转 500，细节进日志
        logger.exception("transcription failed")
        raise HTTPException(status_code=500, detail=f"transcription failed: {e}")
    finally:
        try:
            os.unlink(tmp.name)
        except OSError:
            pass

    return {
        "task": "transcribe",
        "language": info.language,
        "duration": round(info.duration, 3),
        # OpenAI 兼容的最小契约就是这个 text 字段（VoiceTranscriptionService 只读它）
        "text": "".join(s["text"] for s in items),
        "segments": items,
    }


if __name__ == "__main__":
    import uvicorn
    port = int(os.getenv("PORT", "8890"))
    logger.info("Local ASR starting on 127.0.0.1:%s (model=%s)", port, MODEL)
    uvicorn.run(app, host="127.0.0.1", port=port, log_level="info")
