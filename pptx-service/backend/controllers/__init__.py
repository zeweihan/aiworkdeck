"""Controllers package"""
from .project_controller import project_bp
from .page_controller import page_bp
from .template_controller import template_bp, user_template_bp
from .export_controller import export_bp
from .file_controller import file_bp
from .material_controller import material_bp
from .settings_controller import settings_bp
# [checkba] 存量 pptx 格式识别与操作端点
from .pptx_edit_controller import pptx_edit_bp

__all__ = ['project_bp', 'page_bp', 'template_bp', 'user_template_bp', 'export_bp', 'file_bp', 'material_bp', 'settings_bp', 'pptx_edit_bp']

