// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// dev-board#591：Kokoro 英文音色（af_/bf_）首次合成时，misaki 的 en.G2P 发现缺
// en_core_web_sm 就 spacy.cli.download——向 raw.githubusercontent.com 出网，
// HF_HUB_OFFLINE=1 管不到，联网时还会 pip install 进 App 包内 site-packages。
// 实测（kokoro-runtime 1.0.0）：离线时英文合成 500，中文不受影响。
//
// 三道闸，缺一道这里就红：
//   1. requirements.in 钉死模型 wheel，lock 里同一行（pack 发版照 lock 装进 lib）；
//   2. app.py 在建英文 pipeline 前检查模型在不在，缺了直接报错、不许下载；
//   3. pack-release.yml 的 kokoro 冒烟在「出网必失败」的环境里真跑一次 en.G2P 初始化。
// 真实行为验证在 CI 冒烟（第 3 条）；本文件守的是三处接线不被悄悄删掉。

const test = require('node:test')
const assert = require('node:assert')
const fs = require('node:fs')
const path = require('node:path')

const ROOT = path.join(__dirname, '..', '..')
const read = (rel) => fs.readFileSync(path.join(ROOT, rel), 'utf8')

const MODEL_REQ = 'en-core-web-sm @ https://github.com/explosion/spacy-models/releases/download/en_core_web_sm-3.8.0/en_core_web_sm-3.8.0-py3-none-any.whl'

test('requirements.in 与 requirements.lock 都钉了 en_core_web_sm 的同一个 wheel', () => {
  const lines = (rel) => read(rel).split('\n').map((l) => l.trim())
  assert.ok(lines('kokoro-service/requirements.in').includes(MODEL_REQ), 'requirements.in 缺 en-core-web-sm')
  assert.ok(lines('kokoro-service/requirements.lock').includes(MODEL_REQ),
    'requirements.lock 缺 en-core-web-sm（改完 .in 要重跑 uv pip compile）')
})

test('app.py 在建英文 pipeline 前检查模型，且自身从不调 spacy 下载', () => {
  const src = read('kokoro-service/app.py')
  assert.match(src, /EN_SPACY_MODEL = "en_core_web_sm"/)
  assert.match(src, /spacy\.util\.is_package\(EN_SPACY_MODEL\)/)
  const fn = src.slice(src.indexOf('def _pipeline('), src.indexOf('def _lang_for_voice('))
  const guard = fn.indexOf('_require_en_spacy_model()')
  assert.ok(guard !== -1, '_pipeline 没有调用 _require_en_spacy_model')
  assert.ok(guard < fn.indexOf('KPipeline(lang_code='), '模型检查必须在构造 KPipeline 之前')
  assert.doesNotMatch(src, /spacy\.cli\.download\(|from spacy(\.cli)? import (cli|download)/,
    'app.py 不许调用 spacy 的下载入口')
})

test('pack-release.yml 的 kokoro 冒烟在出网必失败的环境里初始化 en.G2P', () => {
  const wf = read('.github/workflows/pack-release.yml')
  const block = wf.slice(wf.indexOf('kokoro-runtime)'), wf.indexOf('asr-runtime)', wf.indexOf('kokoro-runtime)')))
  assert.match(block, /HTTPS_PROXY=http:\/\/127\.0\.0\.1:9/)
  assert.match(block, /from misaki import en; en\.G2P\(/)
  assert.match(block, /\|\| \{ echo [^}]*exit 1; \}/, 'G2P 失败必须让冒烟失败')
})
