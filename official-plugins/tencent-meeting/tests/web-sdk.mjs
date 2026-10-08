// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
const require = createRequire(new URL('../../../frontend/package.json', import.meta.url));
const { JSDOM } = require('jsdom');
const read = (name) => readFileSync(new URL('../web/' + name, import.meta.url), 'utf8');
const tick = () => new Promise((resolve) => setImmediate(resolve));
const fixture = {
  status: { cliAvailable: true, loggedIn: true, userName: 'Synthetic SDK User' },
  config: { lookbackDays: 14, excludeKeywords: ['synthetic exclusion'] },
  list: { meetings: [{ key: 'sdk-meeting', subject: 'SDK synthetic meeting', meetingCode: '12345' }] },
  detail: { key: 'sdk-meeting', subject: 'SDK synthetic meeting', complete: true, errors: [], paragraphs: [{ speaker: 'Synthetic speaker', text: 'SYNTHETIC_PRIVATE_SOURCE' }], smartMinutes: 'Synthetic minutes' },
  export: { fileId: 31, fileName: 'sdk-source.md', path: '腾讯会议/sdk-source.md' },
  sync: { count: 1, added: 1, errors: [] }
};
async function host(options = {}) {
  const dom = new JSDOM(read('index.html'), { runScripts: 'outside-only', url: 'https://plugin.invalid/' });
  const bridge = [], timers = [];
  let context = { pluginId: 'tencent-meeting', projectId: 7, language: 'en-US', theme: 'light', themeTokens: { '--awd-surface': '#ffffff' } };
  let heldExport = null;
  const parent = { postMessage(message, origin) {
    assert.equal(origin, '*'); bridge.push(message);
    if (message.method === 'tools.invoke' && message.params.args.action === 'export' && options.holdExport) { heldExport = message; return; }
    queueMicrotask(() => respond(message));
  } };
  Object.defineProperty(dom.window, 'parent', { value: parent });
  dom.window.setTimeout = (callback, duration) => { const timer = { callback, duration }; timers.push(timer); return timer; };
  dom.window.clearTimeout = (timer) => { if (timer) timer.cancelled = true; };
  function push(message, source = parent) { dom.window.dispatchEvent(new dom.window.MessageEvent('message', { source, data: message })); }
  function respond(message) {
    let result = {};
    if (message.method === 'tools.invoke') {
      assert.equal(message.params.name, 'tencent_meeting_action');
      const { action, json } = message.params.args;
      const args = JSON.parse(json);
      const data = action === 'sync' && !args.jobId ? { pending: true, jobId: 'sdk-job' } : fixture[action];
      result = { output: JSON.stringify({ success: true, data }) };
    } else if (message.method === 'context.get') result = context;
    else if (message.method === 'events.subscribe') result = { subscribed: message.params.events };
    push({ awd: 1, type: 'result', seq: message.seq, ok: true, result });
  }
  dom.window.eval(read('awd-plugin-sdk.js'));
  dom.window.eval(read('panel.js'));
  push({ awd: 1, type: 'init', context }); await tick();
  const $ = (id) => dom.window.document.getElementById(id);
  return {
    dom, $, bridge, timers, push,
    click: async (id) => { $(id).click(); await tick(); },
    select: async () => { dom.window.document.querySelector('.meeting').click(); await tick(); },
    switch: async (next) => { context = { ...context, ...next }; push({ awd: 1, type: 'event', event: 'project.switched', data: {} }); await tick(); },
    releaseExport: async () => { respond(heldExport); await tick(); }
  };
}
test('real SDK unwraps tools.invoke output for check, async sync, detail and export before sending a short chat request', async (t) => {
  const p = await host(); t.after(() => p.dom.window.close());
  assert.equal(p.bridge.filter((message) => message.method === 'tools.invoke').length, 0);
  assert.ok(p.bridge.some((message) => message.method === 'events.subscribe' && message.params.events.includes('project.switched')));
  await p.click('refresh'); assert.match(p.$('connection-status').textContent, /Synthetic SDK User/); assert.equal(p.$('lookback').value, '14');
  await p.click('sync'); const timer = p.timers.find((item) => !item.cancelled); assert.equal(timer.duration, 3000); await timer.callback(); await tick();
  const sync = p.bridge.filter((message) => message.method === 'tools.invoke' && message.params.args.action === 'sync');
  assert.equal(sync.length, 2); assert.deepEqual(JSON.parse(sync[1].params.args.json), { jobId: 'sdk-job' });
  await p.select(); assert.match(p.$('transcript').textContent, /SYNTHETIC_PRIVATE_SOURCE/);
  await p.click('draft-minutes');
  const exportIndex = p.bridge.findIndex((message) => message.method === 'tools.invoke' && message.params.args.action === 'export');
  const chatIndex = p.bridge.findIndex((message) => message.method === 'chat.send');
  assert.ok(exportIndex >= 0 && chatIndex > exportIndex);
  const prompt = p.bridge[chatIndex].params.prompt;
  assert.match(prompt, /^Tencent Meeting plugin minutes/); assert.match(prompt, /"fileId":"31"/); assert.doesNotMatch(prompt, /SYNTHETIC_PRIVATE_SOURCE/); assert.ok(prompt.length < 4000);
});
test('real SDK checks parent message source, applies theme tokens and reloads context on project switch', async (t) => {
  const p = await host(); t.after(() => p.dom.window.close()); await p.click('refresh'); await p.select();
  p.push({ awd: 1, type: 'theme', theme: 'dark', tokens: { '--awd-surface': '#222222' } }, {});
  assert.equal(p.dom.window.document.documentElement.dataset.theme, 'light');
  p.push({ awd: 1, type: 'theme', theme: 'dark', tokens: { '--awd-surface': '#222222' } });
  assert.equal(p.dom.window.document.documentElement.dataset.theme, 'dark'); assert.equal(p.dom.window.document.documentElement.style.getPropertyValue('--awd-surface'), '#222222');
  await p.switch({ projectId: null, language: 'zh-CN' });
  assert.ok(p.bridge.some((message) => message.method === 'context.get')); assert.equal(p.$('detail').hidden, true); assert.equal(p.$('meetings').children.length, 0);
  assert.equal(p.$('refresh').disabled, true); assert.match(p.$('project-notice').textContent, /选择一个项目/); assert.doesNotMatch(p.dom.window.document.body.textContent, /SYNTHETIC_PRIVATE_SOURCE/);
});
test('real SDK late export response cannot send AI chat after the host switches projects', async (t) => {
  const p = await host({ holdExport: true }); t.after(() => p.dom.window.close());
  await p.click('refresh'); await p.select(); await p.click('draft-todos');
  await p.switch({ projectId: 99 }); await p.releaseExport();
  assert.equal(p.bridge.filter((message) => message.method === 'chat.send').length, 0);
});
