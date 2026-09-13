# SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
# SPDX-License-Identifier: AGPL-3.0-or-later
"""
[checkba] 可编辑导出的构造闸（dev-board#617）。

历史病灶：`ServiceConfig.from_defaults` 无 `MINERU_TOKEN` 就直接 ValueError，而桌面发行版
从不注入这个 token（设置页写配置的接口还被 PPTX_SETTINGS_TOKEN 恒 403），于是
`pptx_export_editable` 在发行版里从未成功过、Java 侧捕获后静默降级纯图片导出。

现在的判据是「有引擎可用」：本机 MinerU 在跑就放行，两头都没有才报错，且错误信息指向
本机引擎而不是让用户去申请云端 token。
"""
import pytest

from services.file_parser_service import FileParserService
from services.image_editability.factories import ServiceConfig


def _build(tmp_path, token=None, extractor_method='mineru'):
    return ServiceConfig.from_defaults(
        mineru_token=token,
        upload_folder=str(tmp_path),
        extractor_method=extractor_method,
        inpaint_method='generative',
        max_depth=1,
    )


def test_no_token_but_local_engine_running_builds_config(monkeypatch, tmp_path):
    """无 token + 本机引擎可达 → 正常构造，提取器拿到的解析服务确实没有 token。"""
    monkeypatch.setattr(FileParserService, 'local_service_available', lambda self: True)

    config = _build(tmp_path)

    assert config.upload_folder == tmp_path
    extractor = config.extractor_registry.get_extractor(None)
    assert extractor is not None, '默认提取器必须在场，否则版面分析拿不到元素'
    assert not extractor._parser_service.mineru_token, '本地通路不该依赖任何云端 token'


def test_no_token_and_no_local_engine_raises_pointing_at_local_engine(monkeypatch, tmp_path):
    """无 token + 本机引擎不可达 → ValueError，且信息说清是「本机引擎没起」。"""
    monkeypatch.setattr(FileParserService, 'local_service_available', lambda self: False)

    with pytest.raises(ValueError) as exc:
        _build(tmp_path)

    msg = str(exc.value)
    assert '本机' in msg, f'错误信息要指向本机引擎，实际：{msg}'
    assert 'local' in msg.lower(), f'英文日志里也要认得出是 local service，实际：{msg}'


def test_cloud_token_still_allowed_without_local_engine(monkeypatch, tmp_path):
    """配了云端 token 的部署（docker）不受影响：本机引擎不可达也照常构造。"""
    monkeypatch.setattr(FileParserService, 'local_service_available', lambda self: False)

    config = _build(tmp_path, token='fake-cloud-token')

    extractor = config.extractor_registry.get_extractor(None)
    assert extractor._parser_service.mineru_token == 'fake-cloud-token'


def test_hybrid_extractor_falls_back_to_pure_mineru_without_baidu_key(monkeypatch, tmp_path):
    """默认的 hybrid 会去要百度高精度 OCR（外采）；没配 key 时必须干净回退到纯 MinerU。"""
    monkeypatch.delenv('BAIDU_OCR_API_KEY', raising=False)
    monkeypatch.setattr(FileParserService, 'local_service_available', lambda self: True)

    config = _build(tmp_path, extractor_method='hybrid')

    extractor = config.extractor_registry.get_extractor(None)
    assert type(extractor).__name__ == 'MinerUElementExtractor', \
        '未配百度 key 时不该留下半成品混合提取器'
