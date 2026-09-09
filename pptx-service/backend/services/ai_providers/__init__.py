"""
AI Providers factory module

Provides factory functions to get the appropriate text/image generation providers
based on environment configuration.

Configuration priority (highest → lowest):
    1. Database settings (Flask app.config, persisted via Settings page)
    2. Environment variables (.env file)
    3. Hard-coded defaults

Supported provider formats:
    gemini  — Google AI Studio (API key auth)
    openai  — OpenAI-compatible endpoints
    vertex  — Google Cloud Vertex AI (service-account auth)
    lazyllm — LazyLLM multi-vendor framework
"""
import os
import logging
from typing import Any, Dict, Optional

from .text import TextProvider, GenAITextProvider, OpenAITextProvider, LazyLLMTextProvider
from .image import ImageProvider, GenAIImageProvider, OpenAIImageProvider, LazyLLMImageProvider

logger = logging.getLogger(__name__)

__all__ = [
    'TextProvider', 'GenAITextProvider', 'OpenAITextProvider', 'LazyLLMTextProvider',
    'ImageProvider', 'GenAIImageProvider', 'OpenAIImageProvider', 'LazyLLMImageProvider',
    'get_text_provider', 'get_image_provider', 'get_provider_format'
]


def get_provider_format() -> str:
    """
    Get the configured AI provider format

    Priority:
        1. Flask app.config['AI_PROVIDER_FORMAT'] (from database settings)
        2. Environment variable AI_PROVIDER_FORMAT
        3. Default: 'gemini'

    Returns:
        "gemini", "openai", "vertex" or "lazyllm"
    """
    # Try to get from Flask app config first (database settings)
    try:
        from flask import current_app
        if current_app and hasattr(current_app, 'config'):
            config_value = current_app.config.get('AI_PROVIDER_FORMAT')
            if config_value:
                return str(config_value).lower()
    except RuntimeError:
        # Not in Flask application context
        pass

    # Fallback to environment variable
    return os.getenv('AI_PROVIDER_FORMAT', 'gemini').lower()


def _resolve_setting(key: str, fallback: Optional[str] = None) -> Optional[str]:
    """Look up a configuration value using the standard priority chain.

    Resolution order:
        1. Flask ``app.config`` (populated from the database Settings page)
        2. OS environment variable
        3. *fallback* argument (may be ``None``)
    """
    # 1) Try Flask app.config
    try:
        from flask import current_app
        if current_app and hasattr(current_app, 'config') and key in current_app.config:
            val = current_app.config[key]
            if val is not None:
                logger.debug("Setting %s resolved from app.config", key)
                return str(val)
    except RuntimeError:
        pass  # outside Flask request context

    # 2) Try environment
    env_val = os.getenv(key)
    if env_val is not None:
        logger.debug("Setting %s resolved from environment", key)
        return env_val

    # 3) Fallback
    if fallback is not None:
        logger.debug("Setting %s using fallback: %s", key, fallback)
    return fallback


def _build_provider_config() -> Dict[str, Any]:
    """Assemble provider-specific configuration dict.

    Returns a dict always containing ``'format'`` plus format-specific keys:
        - gemini / openai → ``api_key``, ``api_base``
        - vertex          → ``project_id``, ``location``
        - lazyllm         → ``text_source``, ``image_source``

    Raises ``ValueError`` when required settings are missing.
    """
    fmt = get_provider_format()
    cfg: Dict[str, Any] = {'format': fmt}

    if fmt == 'openai':
        cfg['api_key'] = _resolve_setting('OPENAI_API_KEY') or _resolve_setting('GOOGLE_API_KEY')
        cfg['api_base'] = _resolve_setting('OPENAI_API_BASE', 'https://aihubmix.com/v1')
        if not cfg['api_key']:
            raise ValueError(
                "OPENAI_API_KEY or GOOGLE_API_KEY (from database settings or environment) "
                "is required when AI_PROVIDER_FORMAT=openai."
            )
        logger.info("Provider config — format: openai, api_base: %s", cfg['api_base'])

    elif fmt == 'vertex':
        cfg['project_id'] = _resolve_setting('VERTEX_PROJECT_ID')
        cfg['location'] = _resolve_setting('VERTEX_LOCATION', 'us-central1')
        if not cfg['project_id']:
            raise ValueError(
                "VERTEX_PROJECT_ID must be set when AI_PROVIDER_FORMAT=vertex. "
                "Make sure GOOGLE_APPLICATION_CREDENTIALS points to a valid service-account JSON."
            )
        logger.info("Provider config — format: vertex, project: %s, location: %s",
                     cfg['project_id'], cfg['location'])

    elif fmt == 'lazyllm':
        cfg['text_source'] = _resolve_setting('TEXT_MODEL_SOURCE', 'deepseek')
        cfg['image_source'] = _resolve_setting('IMAGE_MODEL_SOURCE', 'doubao')
        logger.info("Provider config — format: lazyllm, text_source: %s, image_source: %s",
                     cfg['text_source'], cfg['image_source'])

    else:
        # gemini (default) or unknown format
        if fmt != 'gemini':
            logger.warning("Unknown provider format '%s', falling back to gemini", fmt)
            cfg['format'] = 'gemini'
        cfg['api_key'] = _resolve_setting('GOOGLE_API_KEY')
        cfg['api_base'] = _resolve_setting('GOOGLE_API_BASE')
        if not cfg['api_key']:
            raise ValueError("GOOGLE_API_KEY (from database settings or environment) is required")
        logger.info("Provider config — format: gemini, api_base: %s, api_key: %s",
                     cfg['api_base'], '***' if cfg['api_key'] else 'None')

    return cfg


def get_text_provider(model: str = "gemini-3-flash-preview") -> TextProvider:
    """Factory: return the appropriate text-generation provider."""
    cfg = _build_provider_config()
    fmt = cfg['format']

    if fmt == 'openai':
        logger.info("Text provider: OpenAI, model=%s", model)
        return OpenAITextProvider(api_key=cfg['api_key'], api_base=cfg['api_base'], model=model)

    elif fmt == 'vertex':
        logger.info("Text provider: Vertex AI, model=%s, project=%s", model, cfg['project_id'])
        return GenAITextProvider(
            model=model,
            vertexai=True,
            project_id=cfg['project_id'],
            location=cfg['location'],
        )

    elif fmt == 'lazyllm':
        src = cfg.get('text_source', 'deepseek')
        logger.info("Text provider: LazyLLM, model=%s, source=%s", model, src)
        return LazyLLMTextProvider(source=src, model=model)

    else:
        # gemini (default)
        logger.info("Text provider: Gemini, model=%s", model)
        return GenAITextProvider(api_key=cfg['api_key'], api_base=cfg['api_base'], model=model)


def get_image_provider(model: str = "gemini-3-pro-image-preview") -> ImageProvider:
    """Factory: return the appropriate image-generation provider.

    Note: OpenAI format does NOT support 4K resolution — only 1K is available.
    Use Gemini or Vertex AI for higher resolution output.
    """
    cfg = _build_provider_config()
    fmt = cfg['format']

    if fmt == 'openai':
        logger.info("Image provider: OpenAI, model=%s", model)
        logger.warning("OpenAI format only supports 1K resolution, 4K is not available")
        return OpenAIImageProvider(api_key=cfg['api_key'], api_base=cfg['api_base'], model=model)

    elif fmt == 'vertex':
        logger.info("Image provider: Vertex AI, model=%s, project=%s", model, cfg['project_id'])
        return GenAIImageProvider(
            model=model,
            vertexai=True,
            project_id=cfg['project_id'],
            location=cfg['location'],
        )

    elif fmt == 'lazyllm':
        src = cfg.get('image_source', 'doubao')
        logger.info("Image provider: LazyLLM, model=%s, source=%s", model, src)
        return LazyLLMImageProvider(source=src, model=model)

    else:
        # gemini (default)
        logger.info("Image provider: Gemini, model=%s", model)
        return GenAIImageProvider(api_key=cfg['api_key'], api_base=cfg['api_base'], model=model)
