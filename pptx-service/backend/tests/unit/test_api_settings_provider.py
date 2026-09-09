"""
Settings controller tests for provider format handling.
"""

import os
from types import SimpleNamespace
from unittest.mock import MagicMock, patch

from flask import Flask

from controllers.settings_controller import update_settings, verify_api_key


def _build_settings(**overrides):
    defaults = {
        'ai_provider_format': 'gemini',
        'api_key': None,
        'api_base_url': None,
        'text_model': None,
    }
    defaults.update(overrides)

    settings = SimpleNamespace(**defaults)
    settings.to_dict = lambda: {
        'ai_provider_format': settings.ai_provider_format,
        'api_key_length': len(settings.api_key) if settings.api_key else 0,
    }
    return settings


def test_update_settings_accepts_lazyllm_provider():
    """`lazyllm` should be accepted as a valid provider format.

    [checkba] 上游这条用例裸调 update_settings()，而我们给写设置加了
    `_reject_without_settings_token()`（PPTX_SETTINGS_TOKEN 未配置即 403，见
    UPGRADE_CHECKBA.md）。这里补上口令与请求头，让它仍然测「lazyllm 是合法取值」这件事本身。
    """
    app = Flask(__name__)

    settings = _build_settings()
    with app.app_context(), patch.dict(os.environ, {'PPTX_SETTINGS_TOKEN': 'test-token'}):
        with app.test_request_context('/api/settings/', method='PUT', json={'ai_provider_format': 'lazyllm'},
                                      headers={'X-Settings-Token': 'test-token'}):
            with patch('controllers.settings_controller.Settings.get_settings', return_value=settings):
                with patch('controllers.settings_controller.db.session.commit'):
                    with patch('controllers.settings_controller._sync_settings_to_config'):
                        response, status_code = update_settings()

    assert status_code == 200
    data = response.get_json()
    assert data['success'] is True
    assert data['data']['ai_provider_format'] == 'lazyllm'


def test_verify_uses_configured_text_model():
    """Verify endpoint should use configured text model, not a hardcoded gemini model."""
    app = Flask(__name__)
    app.config.update(
        TEXT_MODEL='gemini-3-flash-preview',
        AI_PROVIDER_FORMAT='lazyllm',
    )

    settings = _build_settings(ai_provider_format='lazyllm', text_model='deepseek-chat')
    mock_provider = MagicMock()
    mock_provider.generate_text.return_value = 'OK'

    with app.app_context():
        with app.test_request_context('/api/settings/verify', method='POST'):
            with patch('controllers.settings_controller.Settings.get_settings', return_value=settings):
                with patch('services.ai_providers.get_text_provider', return_value=mock_provider) as mock_get_provider:
                    response, status_code = verify_api_key()

    assert status_code == 200
    data = response.get_json()
    assert data['success'] is True
    assert data['data']['available'] is True
    mock_get_provider.assert_called_once_with(model='deepseek-chat')
    mock_provider.generate_text.assert_called_once()
