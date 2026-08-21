"""启动对账：进程重启后遗留的 PENDING/PROCESSING 任务不能永远挂着。

病灶：active_tasks 是进程内字典，重启即丢；数据库里那些行还停在 PENDING/PROCESSING，
而跑它们的执行器已经不存在。前端轮询那个 task_id 会一直转下去——没有报错、没有超时、
也没有任何恢复途径，除非用户自己察觉并重开一个任务。

这里刻意不用 conftest 的 app fixture：那个 fixture 走 create_app()，会连带 import
整条控制器链（pdf2docx 等重依赖）。本条测的是对账逻辑本身，只需要 models 与一个
最小 Flask 应用，跑得起来也跑得快。
"""
import uuid

import pytest
from flask import Flask

from models import db, Task, Project
from services.task_manager import TaskManager


@pytest.fixture()
def minimal_app(tmp_path):
    app = Flask(__name__)
    app.config['SQLALCHEMY_DATABASE_URI'] = f"sqlite:///{tmp_path / 'reconcile.db'}"
    app.config['SQLALCHEMY_TRACK_MODIFICATIONS'] = False
    db.init_app(app)
    with app.app_context():
        db.create_all()
        yield app


def _make_task(status):
    project = Project(id=str(uuid.uuid4()), idea_prompt='对账测试')
    db.session.add(project)
    task = Task(id=str(uuid.uuid4()), project_id=project.id,
                task_type='GENERATE_IMAGES', status=status)
    db.session.add(task)
    db.session.commit()
    return task.id


def test_orphaned_tasks_are_failed_on_startup(minimal_app):
    pending_id = _make_task('PENDING')
    processing_id = _make_task('PROCESSING')
    done_id = _make_task('COMPLETED')

    changed = TaskManager.reconcile_orphaned_tasks()
    assert changed == 2

    for tid in (pending_id, processing_id):
        task = db.session.get(Task, tid)
        assert task.status == 'FAILED', '重启遗留的任务必须落到可见的失败终态'
        assert task.error_message, '要写明原因，否则用户不知道该重试'
        assert task.completed_at is not None

    # 已经结束的任务不许被动
    assert db.session.get(Task, done_id).status == 'COMPLETED'


def test_reconcile_is_idempotent(minimal_app):
    _make_task('PENDING')
    assert TaskManager.reconcile_orphaned_tasks() == 1
    assert TaskManager.reconcile_orphaned_tasks() == 0
