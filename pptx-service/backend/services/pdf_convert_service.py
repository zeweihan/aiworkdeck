"""
[checkba] PDF 转换服务：

- to_docx: 文本型 PDF 的版式级转 Word（pdf2docx：段落/表格/图片/分栏尽量保留原排版）
- ocr_markdown: 扫描件（或任意 PDF）经 MinerU 解析出 markdown——本地 mineru-service
  优先（桌面捆绑，MINERU_LOCAL_URL 注入）、云端 MinerU 兜底（需 MINERU_TOKEN），
  复用 FileParserService 的既有路由，不引入第三方云 OCR。

供主仓 backend PdfTools.pdf_to_word 调用（本地同机部署，传本地文件路径）。
"""
import logging
import os

import fitz  # pymupdf（pdf2docx 依赖自带）
from flask import current_app
from pdf2docx import Converter

from config import Config
from services.file_parser_service import FileParserService

logger = logging.getLogger(__name__)


class PdfConvertError(Exception):
    """转换错误（带上下文信息，控制器直接回给调用方）"""


def _validate(pdf_path: str):
    if not pdf_path or not os.path.exists(pdf_path):
        raise PdfConvertError(f"文件不存在: {pdf_path}")
    if not pdf_path.lower().endswith('.pdf'):
        raise PdfConvertError(f"仅支持 .pdf 文件: {pdf_path}")


def to_docx(pdf_path: str, output_path: str) -> dict:
    """版式级 PDF→docx。仅适用于有文本层的未加密 PDF；扫描件请走 ocr_markdown。"""
    _validate(pdf_path)
    if not output_path or not output_path.lower().endswith('.docx'):
        raise PdfConvertError(f"output_path 必须是 .docx 路径: {output_path}")

    doc = fitz.open(pdf_path)
    try:
        if doc.needs_pass:
            raise PdfConvertError("PDF 已加密，无法转换")
        pages = doc.page_count
        text_chars = sum(len(page.get_text()) for page in doc)
    finally:
        doc.close()

    if text_chars < 20:
        raise PdfConvertError("该 PDF 没有文本层（扫描件），版式级转换不适用，请改用 /api/pdf/ocr-markdown")

    parent = os.path.dirname(output_path)
    if parent:
        os.makedirs(parent, exist_ok=True)

    logger.info(f"pdf2docx converting: {pdf_path} -> {output_path} ({pages} pages)")
    cv = Converter(pdf_path)
    try:
        cv.convert(output_path)
    finally:
        cv.close()

    if not os.path.exists(output_path) or os.path.getsize(output_path) == 0:
        raise PdfConvertError("pdf2docx 转换未产出有效文件")
    return {'output_path': output_path, 'pages': pages}


def ocr_markdown(pdf_path: str) -> dict:
    """扫描件 OCR：MinerU 解析出 markdown（本地服务优先，云端兜底）。"""
    _validate(pdf_path)

    parser = FileParserService(
        mineru_token=current_app.config.get("MINERU_TOKEN", ""),
        mineru_api_base=current_app.config.get("MINERU_API_BASE", ""),
        google_api_key=current_app.config.get("GOOGLE_API_KEY", ""),
        google_api_base=current_app.config.get("GOOGLE_API_BASE", ""),
        openai_api_key=current_app.config.get("OPENAI_API_KEY", ""),
        openai_api_base=current_app.config.get("OPENAI_API_BASE", ""),
        image_caption_model=current_app.config.get("IMAGE_CAPTION_MODEL", Config.IMAGE_CAPTION_MODEL),
        provider_format=current_app.config.get("AI_PROVIDER_FORMAT", "gemini"),
    )

    _batch_id, markdown, _extract_id, error, _failed = parser.parse_file(
        pdf_path, os.path.basename(pdf_path))

    if not markdown or not markdown.strip():
        raise PdfConvertError(
            error or "MinerU 未返回解析内容（本地 mineru-service 未运行且未配置云端 MINERU_TOKEN？）")
    return {'markdown': markdown}
