// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
const require = createRequire(new URL('../../../frontend/package.json', import.meta.url));
const { JSDOM } = require('jsdom');
const html = readFileSync(new URL('../web/index.html', import.meta.url), 'utf8');
const source = readFileSync(new URL('../web/panel.js', import.meta.url), 'utf8');
const tick = () => new Promise((resolve) => setImmediate(resolve));
const fixture = {
  status: { cliAvailable: true, loggedIn: true, userName: 'Synthetic User' },
  config: { lookbackDays: 30, excludeKeywords: [] },
  list: { meetings: [{ key: 'meeting-one', subject: '<img src=x onerror=alert(1)> 合成会议', meetingCode: '10001', startTime: 1791000000 }] },
  detail: { complete: true, errors: [], key: 'meeting-one', subject: '合成会议', paragraphs: [{ speaker: 'Synthetic speaker', text: 'PRIVATE_FIXTURE_TRANSCRIPT', startTime: '00:03' }], smartMinutes: '<script>PRIVATE_FIXTURE_MINUTES</script>' },
  export: { fileId: 17, fileName: '合成会议.md', path: '腾讯会议/合成会议.md' },
  sync: { count: 1, added: 1, errors: [] },
  logout: {}, login: { authorizeUrl: 'https://meeting.tencent.com/auth?code=synthetic' }
};
async function panel(options = {}) {
  const dom = new JSDOM(html, { runScripts: 'outside-only', url: 'https://plugin.invalid/' });
  const calls = [], chats = [], events = {}, timers = [];
  let context = { projectId: 1, language: 'zh-CN', ...options.context };
  dom.window.setTimeout = (callback, duration) => { const timer = { callback, duration }; timers.push(timer); return timer; };
  dom.window.clearTimeout = (timer) => { if (timer) timer.cancelled = true; };
  dom.window.awd = {
    ready: async () => context,
    tools: { invoke: async (name, args) => {
      const entry = { name, action: args.action, params: JSON.parse(args.json) }; calls.push(entry);
      const result = options.invoke ? await options.invoke(entry) : undefined;
      return typeof result === 'string' ? result : JSON.stringify({ success: true, data: result ?? fixture[entry.action] });
    } },
    chat: { send: async (prompt) => { chats.push(prompt); } },
    events: { on: (name, callback) => { events[name] = callback; } },
    call: async () => context
  };
  dom.window.eval(source); await tick();
  const $ = (id) => dom.window.document.getElementById(id);
  return {
    dom, $, calls, chats, timers,
    click: async (id) => { $(id).click(); await tick(); },
    select: async () => { dom.window.document.querySelector('.meeting').click(); await tick(); },
    switch: async (next) => { context = next; await events['project.switched'](); await tick(); },
    timer: async () => { const timer = timers.find((item) => !item.cancelled && !item.ran); if (!timer) return false; timer.ran = true; await timer.callback(); await tick(); return true; }
  };
}
test('initial load is passive and projectless state blocks all tool calls', async (t) => {
  const p = await panel({ context: { projectId: null } }); t.after(() => p.dom.window.close());
  assert.equal(p.calls.length, 0); assert.match(p.$('project-notice').textContent, /选择一个项目/);
  for (const id of ['refresh', 'login', 'sync', 'save-config']) await p.click(id);
  assert.equal(p.calls.length, 0);
});
test('manual connection check loads settings and searchable safe text; transcript and minutes remain text', async (t) => {
  const p = await panel(); t.after(() => p.dom.window.close());
  assert.equal(p.calls.length, 0); await p.click('refresh');
  assert.deepEqual(p.calls.map((call) => call.action), ['status', 'config', 'list']);
  assert.equal(p.dom.window.document.querySelectorAll('#meetings img').length, 0);
  p.$('search').value = 'unmatched'; p.$('search').dispatchEvent(new p.dom.window.Event('input'));
  assert.equal(p.dom.window.document.querySelectorAll('.meeting').length, 0);
  p.$('search').value = '10001'; p.$('search').dispatchEvent(new p.dom.window.Event('input'));
  await p.select(); assert.match(p.$('transcript').textContent, /PRIVATE_FIXTURE_TRANSCRIPT/);
  await p.click('minutes-tab'); assert.equal(p.$('minutes').hidden, false);
  assert.equal(p.$('minutes').querySelector('script'), null); assert.match(p.$('minutes').textContent, /PRIVATE_FIXTURE_MINUTES/);
});
test('AI actions export first and send only a short file reference with market skill prefix', async (t) => {
  const p = await panel(); t.after(() => p.dom.window.close());
  await p.click('refresh'); await p.select(); await p.click('draft-minutes'); await p.click('draft-todos');
  assert.equal(p.calls.filter((call) => call.action === 'export').length, 2);
  assert.equal(p.chats.length, 2); assert.match(p.chats[0], /^腾讯会议插件纪要/); assert.match(p.chats[1], /^腾讯会议插件待办/);
  for (const prompt of p.chats) {
    assert.ok(prompt.length < 4000); assert.match(prompt, /"fileId":"17"/); assert.match(prompt, /腾讯会议\/合成会议.md/);
    assert.doesNotMatch(prompt, /PRIVATE_FIXTURE/);
  }
});
test('failed exports never send AI requests or display raw backend errors', async (t) => {
  const p = await panel({ invoke: ({ action }) => action === 'export' ? JSON.stringify({ success: false, error: { token: 'SECRET_TOKEN_FIXTURE' } }) : undefined });
  t.after(() => p.dom.window.close()); await p.click('refresh'); await p.select(); await p.click('draft-minutes');
  assert.equal(p.chats.length, 0); assert.doesNotMatch(p.dom.window.document.body.textContent, /SECRET_TOKEN_FIXTURE/);
  assert.equal(p.$('feedback').dataset.error, 'true');
});
test('long actions poll the same job without relaunching it', async (t) => {
  const p = await panel({ invoke: ({ action, params }) => action === 'sync' && !params.jobId ? { pending: true, jobId: 'job-one' } : undefined });
  t.after(() => p.dom.window.close()); await p.click('refresh'); await p.click('sync');
  assert.equal(p.$('sync').disabled, true); assert.equal(p.timers.at(-1).duration, 3000); await p.timer();
  assert.deepEqual(p.calls.filter((call) => call.action === 'sync').map((call) => call.params), [{}, { jobId: 'job-one' }]);
  assert.equal(p.$('sync').disabled, false); assert.match(p.$('feedback').textContent, /同步完成/);
});
test('project switching discards pending export and does not send chat to another project', async (t) => {
  let resolveExport;
  const p = await panel({ invoke: ({ action }) => action === 'export' ? new Promise((resolve) => { resolveExport = resolve; }) : undefined });
  t.after(() => p.dom.window.close()); await p.click('refresh'); await p.select(); await p.click('draft-minutes');
  await p.switch({ projectId: 2, language: 'en-US' }); resolveExport(fixture.export); await tick();
  assert.equal(p.chats.length, 0); assert.equal(p.$('detail').hidden, true); assert.equal(p.$('meetings').children.length, 0);
  assert.doesNotMatch(p.dom.window.document.body.textContent, /PRIVATE_FIXTURE/); assert.equal(p.dom.window.document.documentElement.lang, 'en-US');
});
test('save settings validates the range and sends normalized exclusion words', async (t) => {
  const p = await panel(); t.after(() => p.dom.window.close());
  p.$('lookback').value = '0'; await p.click('save-config'); assert.equal(p.calls.length, 0);
  p.$('lookback').value = '91'; await p.click('save-config'); assert.equal(p.calls.length, 0);
  assert.equal(p.$('lookback').max, '90');
  p.$('lookback').value = '90'; p.$('exclude').value = ' internal \ninternal\ntest'; await p.click('save-config');
  assert.deepEqual(p.calls[0].params, { lookbackDays: 90, excludeKeywords: ['internal', 'test'] });
});
test('login links are copyable and status polling is bounded to the login wait', async (t) => {
  const p = await panel({ invoke: ({ action }) => action === 'status' ? { cliAvailable: true, loggedIn: false } : undefined });
  t.after(() => p.dom.window.close()); await p.click('login');
  assert.equal(p.$('authorization').hidden, false); assert.equal(p.$('authorize-url').readOnly, true);
  for (let i = 0; i < 41; i++) await p.timer();
  assert.equal(p.calls.filter((call) => call.action === 'status').length, 40);
  assert.match(p.$('login-wait').textContent, /已停止自动检查/); assert.equal(await p.timer(), false);
  await p.click('copy-url'); assert.match(p.$('feedback').textContent, /手动复制/);
});
test('authorization rejects non-Tencent URLs and English AI uses the correct skill prefix', async (t) => {
  const unsafe = await panel({ invoke: ({ action }) => action === 'login' ? { authorizeUrl: 'https://meeting.tencent.com.evil.invalid/auth' } : undefined });
  t.after(() => unsafe.dom.window.close()); await unsafe.click('login'); assert.equal(unsafe.$('authorization').hidden, true);
  const p = await panel({ context: { language: 'en-US' } }); t.after(() => p.dom.window.close());
  await p.click('refresh'); await p.select(); await p.click('draft-todos');
  assert.match(p.chats[0], /^Tencent Meeting plugin action items/); assert.equal(p.$('sync').textContent, 'Sync meetings');
});
test('a project switch stops a pending long-job poll and clears the authorization URL', async (t) => {
  const p = await panel({ invoke: ({ action }) => action === 'sync' ? { pending: true, jobId: 'job-one' } : undefined });
  t.after(() => p.dom.window.close()); await p.click('login'); await p.click('refresh'); await p.click('sync');
  await p.switch({ projectId: 2 }); await p.timer();
  assert.equal(p.calls.filter((call) => call.action === 'sync').length, 1); assert.equal(p.$('authorize-url').value, '');
});

test('long-job waiting stops after 90 polls and never relaunches the operation', async (t) => {
  const p = await panel({ invoke: ({ action }) => action === 'sync' ? { pending: true, jobId: 'job-stuck' } : undefined });
  t.after(() => p.dom.window.close()); await p.click('refresh'); await p.click('sync');
  for (let i = 0; i < 90; i++) await p.timer();
  const syncCalls = p.calls.filter((call) => call.action === 'sync');
  assert.equal(syncCalls.length, 91);
  assert.equal(syncCalls.filter((call) => !call.params.jobId).length, 1);
  assert.equal(p.$('sync').disabled, false); assert.match(p.$('feedback').textContent, /已停止等待/);
  assert.equal(await p.timer(), false);
});

test('sign out requires an explicit shared CLI confirmation and cancellation makes no tool call', async (t) => {
  const p = await panel(); t.after(() => p.dom.window.close()); await p.click('refresh'); await p.click('logout');
  assert.equal(p.$('logout-confirmation').hidden, false); assert.match(p.$('logout-confirmation').textContent, /本机共享/);
  assert.equal(p.calls.filter((call) => call.action === 'logout').length, 0);
  await p.click('cancel-logout'); assert.equal(p.$('logout-confirmation').hidden, true);
  assert.equal(p.calls.filter((call) => call.action === 'logout').length, 0);
  await p.click('logout'); await p.click('confirm-logout');
  assert.deepEqual(p.calls.find((call) => call.action === 'logout').params, { confirmed: true });
  assert.equal(p.$('logout-confirmation').hidden, true); assert.equal(p.$('sync').disabled, true);
});
test('incomplete meeting material is labelled, errors render as text, and export or AI actions are blocked', async (t) => {
  const p = await panel({ invoke: ({ action }) => action === 'detail' ? { ...fixture.detail, complete: false, errors: ['<img src=x> 合成读取失败'] } : undefined });
  t.after(() => p.dom.window.close()); await p.click('refresh'); await p.select();
  assert.equal(p.$('completeness').hidden, false); assert.match(p.$('completeness').textContent, /尚未完整读取/);
  assert.match(p.$('completeness').textContent, /合成读取失败/); assert.equal(p.$('completeness').querySelector('img'), null);
  for (const id of ['export', 'draft-minutes', 'draft-todos']) { assert.equal(p.$(id).disabled, true); await p.click(id); }
  assert.equal(p.calls.filter((call) => call.action === 'export').length, 0); assert.equal(p.chats.length, 0);
  await p.switch({ projectId: 2 }); assert.equal(p.$('completeness').hidden, true); assert.equal(p.$('completeness').textContent, '');
});
