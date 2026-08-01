"""
[checkba] PDF Convert Controller - PDF 转换端点。

供主仓 backend PdfTools 调用（本地桌面同机部署，传本地文件路径）：
- POST /api/pdf/to-docx      {pdf_path, output_path}  -> 版式级转 Word（pdf2docx，限文本型）
- POST /api/pdf/ocr-markdown {pdf_path}               -> 扫描件 MinerU OCR 出 markdown（本地优先）
"""
from flask import Blueprint, request

from utils import success_response, error_response
from services import pdf_convert_service

pdf_convert_bp = Blueprint('pdf_convert', __name__, url_prefix='/api/pdf')


@pdf_convert_bp.route('/to-docx', methods=['POST'])
def pdf_to_docx():
    data = request.get_json(silent=True) or {}
    try:
        result = pdf_convert_service.to_docx(data.get('pdf_path'), data.get('output_path'))
        return success_response(result)
    except pdf_convert_service.PdfConvertError as e:
        return error_response('INVALID_REQUEST', str(e), 400)
    except Exception as e:
        return error_response('SERVER_ERROR', str(e), 500)


@pdf_convert_bp.route('/ocr-markdown', methods=['POST'])
def pdf_ocr_markdown():
    data = request.get_json(silent=True) or {}
    try:
        result = pdf_convert_service.ocr_markdown(data.get('pdf_path'))
        return success_response(result)
    except pdf_convert_service.PdfConvertError as e:
        return error_response('INVALID_REQUEST', str(e), 400)
    except Exception as e:
        return error_response('SERVER_ERROR', str(e), 500)
