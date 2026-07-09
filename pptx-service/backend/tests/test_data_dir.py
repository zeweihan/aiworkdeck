"""PPTX_DATA_DIR: packaged desktop mode writes DB/uploads outside read-only resources"""
import os


def test_data_dir_env_redirects_db_and_uploads(tmp_path, monkeypatch):
    monkeypatch.setenv('PPTX_DATA_DIR', str(tmp_path))
    import app as app_module
    application = app_module.create_app()
    expected_db = os.path.join(str(tmp_path), 'instance', 'database.db')
    assert application.config['SQLALCHEMY_DATABASE_URI'] == f'sqlite:///{expected_db}'
    assert application.config['UPLOAD_FOLDER'] == os.path.join(str(tmp_path), 'uploads')
    assert os.path.isdir(os.path.join(str(tmp_path), 'instance'))
    assert os.path.isdir(os.path.join(str(tmp_path), 'uploads'))


def test_without_env_keeps_legacy_paths(monkeypatch):
    monkeypatch.delenv('PPTX_DATA_DIR', raising=False)
    import app as app_module
    application = app_module.create_app()
    backend_dir = os.path.dirname(os.path.abspath(app_module.__file__))
    assert application.config['SQLALCHEMY_DATABASE_URI'].endswith(
        os.path.join(backend_dir, 'instance', 'database.db'))
    assert application.config['UPLOAD_FOLDER'] == os.path.join(
        os.path.dirname(backend_dir), 'uploads')
