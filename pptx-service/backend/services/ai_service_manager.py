"""
AIService singleton manager for optimizing provider initialization

This module provides a singleton pattern implementation for AIService to avoid
repeated initialization of AI providers (TextProvider and ImageProvider) on every request.

Benefits:
- Reuses AI provider instances across requests
- Reduces initialization overhead
- Better resource management
- Thread-safe for Flask multi-threaded environment

Usage:
    from services.ai_service_manager import get_ai_service

    # In your controller
    ai_service = get_ai_service()
    outline = ai_service.generate_outline(project_context)

    # [checkba] 主后端下发的动态模型配置（见下方 model_config 段）
    ai_service = get_ai_service(model_config=request.get_json().get('model_config'))
"""

import logging
from threading import Lock
from typing import Any, Dict, Optional
from flask import current_app, has_app_context
from .ai_service import AIService
from .ai_providers import get_text_provider, get_image_provider, TextProvider, ImageProvider
from .ai_providers.text import OpenAITextProvider, GenAITextProvider
from .ai_providers.image import OpenAIImageProvider, GenAIImageProvider

logger = logging.getLogger(__name__)

# Global singleton instance
_ai_service_instance: Optional[AIService] = None
_lock = Lock()

# Provider cache to avoid re-initialization when models don't change
_text_provider_cache: dict = {}
_image_provider_cache: dict = {}
_cache_lock = Lock()

# ==================== [checkba] 动态模型配置（model_config） ====================
# checkba 主后端（PptxTools）在每个生成请求体里带 model_config，让 pptx-service 用
# 与 AI 对话完全一致的供应商/密钥/模型，而不是自己那份 GOOGLE_API_KEY——桌面端根本没有
# 配置它的入口。这段定制在 0.4.0 re-vendor（PR#129）时随上游整包替换被丢掉，
# 大纲阶段一律抛 "GOOGLE_API_KEY is required"，AI PPT 整条链路断掉。
# re-vendor 时必须重新移植，见 UPGRADE_CHECKBA.md 定制清单。

# 按配置指纹缓存的 AIService：一次生成里大纲/描述/图片会反复取，图片阶段还在线程池里
# 逐页取，不缓存等于每页新建一个 OpenAI 客户端。
_dynamic_service_cache: Dict[tuple, AIService] = {}

# 最近一次收到的 model_config。深层调用点（task_manager 子线程、
# image_editability/factories.py 的 create_* 工厂）拿不到请求体，只能靠这个进程级兜底。
# 也正因为它是进程级的，pptx-service 必须保持「单用户本机进程」形态（桌面端就是如此）。
_active_model_config: Optional[Dict[str, Any]] = None


def _get_cached_text_provider(model: str) -> TextProvider:
    """
    Get or create a cached text provider instance
    
    Args:
        model: Model name to use
        
    Returns:
        Cached or new TextProvider instance
    """
    with _cache_lock:
        if model not in _text_provider_cache:
            logger.info(f"Creating new TextProvider for model: {model}")
            _text_provider_cache[model] = get_text_provider(model=model)
        else:
            logger.debug(f"Reusing cached TextProvider for model: {model}")
        return _text_provider_cache[model]


def _get_cached_image_provider(model: str) -> ImageProvider:
    """
    Get or create a cached image provider instance
    
    Args:
        model: Model name to use
        
    Returns:
        Cached or new ImageProvider instance
    """
    with _cache_lock:
        if model not in _image_provider_cache:
            logger.info(f"Creating new ImageProvider for model: {model}")
            _image_provider_cache[model] = get_image_provider(model=model)
        else:
            logger.debug(f"Reusing cached ImageProvider for model: {model}")
        return _image_provider_cache[model]


def _model_config_key(model_config: Dict[str, Any]) -> tuple:
    """[checkba] 动态 AIService 的缓存键：配置任一项变了就换实例。"""
    return (
        (model_config.get('provider') or 'openai').lower(),
        model_config.get('api_key'),
        model_config.get('api_base'),
        model_config.get('text_model'),
        model_config.get('image_model'),
    )


def set_active_model_config(model_config: Optional[Dict[str, Any]]) -> None:
    """
    [checkba] 记下主后端下发的 model_config，供拿不到请求体的深层调用点兜底。

    空值不覆盖：refine/tasks 这类不带 model_config 的请求不该把已有配置清掉。
    """
    global _active_model_config
    if not model_config:
        return
    with _cache_lock:
        _active_model_config = dict(model_config)
    logger.info(
        "[checkba] active model_config updated: provider=%s, text_model=%s, image_model=%s",
        model_config.get('provider'), model_config.get('text_model'), model_config.get('image_model')
    )


def create_ai_service_with_config(model_config: Dict[str, Any]) -> AIService:
    """
    [checkba] 用主后端下发的配置创建 AIService（按配置指纹缓存）。

    Args:
        model_config: 主后端下发的配置，键与 PptxServiceClient.ModelConfig.toJson() 对齐：
            - provider: 'openai'（OpenAI 兼容，OpenRouter 走这档）或 'gemini'
            - api_key / api_base: 供应商密钥与地址
            - text_model: 文本模型（大纲、页面描述）
            - image_model: 图像生成模型（幻灯片图、可编辑导出的干净背景图）

    Raises:
        ValueError: 配置不完整。这里刻意不回退到本服务自己的 GOOGLE_API_KEY——
            那会让「主后端没下发密钥」这种接线故障退化成一条难查的模型错误。
    """
    provider = (model_config.get('provider') or 'openai').lower()
    api_key = model_config.get('api_key')
    api_base = model_config.get('api_base')
    text_model = model_config.get('text_model')
    image_model = model_config.get('image_model')

    if not api_key:
        raise ValueError("model_config.api_key is required (主后端未下发密钥)")
    if not text_model or not image_model:
        raise ValueError("model_config.text_model / model_config.image_model are required")

    key = _model_config_key(model_config)
    with _cache_lock:
        cached = _dynamic_service_cache.get(key)
        if cached is not None:
            logger.debug("[checkba] reusing AIService for text_model=%s", text_model)
            return cached

        logger.info(
            "[checkba] creating AIService from model_config: provider=%s, text_model=%s, image_model=%s",
            provider, text_model, image_model
        )
        if provider == 'gemini':
            text_provider = GenAITextProvider(api_key=api_key, api_base=api_base, model=text_model)
            image_provider = GenAIImageProvider(api_key=api_key, api_base=api_base, model=image_model)
        else:
            # 'openai' 格式（OpenRouter / 平台通道均走这档）
            text_provider = OpenAITextProvider(api_key=api_key, api_base=api_base, model=text_model)
            image_provider = OpenAIImageProvider(api_key=api_key, api_base=api_base, model=image_model)

        service = AIService(text_provider=text_provider, image_provider=image_provider)
        _dynamic_service_cache[key] = service
        return service


def get_ai_service(force_new: bool = False, model_config: Optional[Dict[str, Any]] = None) -> AIService:
    """
    Get the singleton AIService instance with optimized provider caching

    This function creates and returns a singleton AIService instance that reuses
    AI providers (TextProvider and ImageProvider) across requests, significantly
    reducing initialization overhead.

    Args:
        force_new: If True, forces creation of a new instance (useful for testing)
        model_config: [checkba] 主后端下发的动态模型配置。请求没带时用最近一次下发的
            配置兜底（见 _active_model_config），两者都没有才走本服务自己的单例配置。

    Returns:
        AIService singleton instance with cached providers

    Note:
        The providers are cached per model name. If TEXT_MODEL or IMAGE_MODEL
        changes in Flask config, new providers will be created automatically.
    """
    global _ai_service_instance

    # [checkba] 动态配置优先于本服务自己的 GOOGLE_API_KEY/TEXT_MODEL
    effective_config = model_config or _active_model_config
    if effective_config:
        if force_new:
            with _cache_lock:
                _dynamic_service_cache.pop(_model_config_key(effective_config), None)
        return create_ai_service_with_config(effective_config)

    if force_new:
        with _lock:
            logger.info("Force creating new AIService instance")
            _ai_service_instance = None
    
    if _ai_service_instance is None:
        with _lock:
            # Double-check locking pattern
            if _ai_service_instance is None:
                logger.info("Initializing AIService singleton with provider caching")
                
                # Get model names from Flask config or use defaults
                from config import get_config
                config = get_config()
                
                if has_app_context() and current_app and hasattr(current_app, "config"):
                    text_model = current_app.config.get("TEXT_MODEL", config.TEXT_MODEL)
                    image_model = current_app.config.get("IMAGE_MODEL", config.IMAGE_MODEL)
                else:
                    text_model = config.TEXT_MODEL
                    image_model = config.IMAGE_MODEL
                
                # Get cached providers
                text_provider = _get_cached_text_provider(text_model)
                image_provider = _get_cached_image_provider(image_model)
                
                # Create AIService with cached providers
                _ai_service_instance = AIService(
                    text_provider=text_provider,
                    image_provider=image_provider
                )
                
                logger.info(f"AIService singleton created with models: text={text_model}, image={image_model}")
    
    return _ai_service_instance


def clear_ai_service_cache():
    """
    Clear the AIService singleton and provider cache
    
    This is useful when:
    - Configuration changes (API keys, endpoints, models)
    - Testing scenarios requiring fresh instances
    - Memory cleanup needed
    
    Note:
    - Uses nested locks to ensure atomic cache clearing operation
    - Prevents race conditions where new instances could be created
      with stale cached providers during the clearing process
    """
    global _ai_service_instance
    
    with _lock:
        _ai_service_instance = None
        logger.info("AIService singleton cache cleared")
        with _cache_lock:
            _text_provider_cache.clear()
            _image_provider_cache.clear()
            # [checkba] 动态实例也要清（密钥/地址改了旧客户端还在用）；
            # 但 _active_model_config 保留——它是主后端的下发口径，不是本服务的缓存。
            _dynamic_service_cache.clear()
            logger.info("Provider cache cleared")


def get_provider_cache_info() -> dict:
    """
    Get information about cached providers (for debugging/monitoring)
    
    Returns:
        Dictionary with cache statistics
    """
    with _cache_lock:
        return {
            "text_providers": list(_text_provider_cache.keys()),
            "image_providers": list(_image_provider_cache.keys()),
            "total_cached": len(_text_provider_cache) + len(_image_provider_cache)
        }
