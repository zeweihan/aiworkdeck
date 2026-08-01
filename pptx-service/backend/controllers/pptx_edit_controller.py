"""
[checkba] PPTX Edit Controller - 存量 pptx 文件的格式识别与操作端点。

供主仓 backend PptxServiceClient 调用（本地桌面同机部署，传本地文件路径），
给 AI 提供与 docx 同级的 PPT 格式能力：
- POST /api/pptx/inspect  {pptx_path}                     -> 结构化格式全览
- POST /api/pptx/format   {pptx_path, ops, [output_path]} -> 批量格式操作
"""
from flask import Blueprint, request

from utils import success_response, error_response
from services import pptx_format_service

pptx_edit_bp = Blueprint('pptx_edit', __name__, url_prefix='/api/pptx')


@pptx_edit_bp.route('/inspect', methods=['POST'])
def inspect_pptx():
    data = request.get_json(silent=True) or {}
    pptx_path = data.get('pptx_path')
    try:
        return success_response(pptx_format_service.inspect(pptx_path))
    except pptx_format_service.PptxFormatError as e:
        return error_response('INVALID_REQUEST', str(e), 400)
    except Exception as e:
        return error_response('SERVER_ERROR', str(e), 500)


@pptx_edit_bp.route('/format', methods=['POST'])
def format_pptx():
    data = request.get_json(silent=True) or {}
    pptx_path = data.get('pptx_path')
    ops = data.get('ops')
    if not isinstance(ops, list) or not ops:
        return error_response('INVALID_REQUEST', 'ops 必须是非空数组', 400)
    try:
        result = pptx_format_service.apply_ops(pptx_path, ops, data.get('output_path'))
        return success_response(result)
    except pptx_format_service.PptxFormatError as e:
        return error_response('INVALID_REQUEST', str(e), 400)
    except Exception as e:
        return error_response('SERVER_ERROR', str(e), 500)
