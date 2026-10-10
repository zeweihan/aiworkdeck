// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const os = require('node:os');
const path = require('node:path');
const { createDroppedFileStore, initDroppedFileService, MAX_BYTES } = require('../main/dropped-files');

test('staged bytes are exact, private, uniquely named, and released by owner token only', async () => {
    const tempDir = await fs.mkdtemp(path.join(os.tmpdir(), 'awd-drop-test-'));
    try {
        const store = createDroppedFileStore({ tempDir });
        const bytes = Uint8Array.from([0, 255, 13, 10]);
        const first = await store.stage(1, { name: 'fixture.txt', bytes });
        const second = await store.stage(1, { name: 'fixture.txt', bytes });
        assert.equal(first.ok, true); assert.equal(second.ok, true);
        assert.notEqual(first.path, second.path);
        assert.deepEqual(await fs.readFile(first.path), Buffer.from(bytes));
        // Windows reports synthesized mode bits; owner/group POSIX permissions apply only on Unix.
        if (process.platform !== 'win32') assert.equal((await fs.stat(first.path)).mode & 0o777, 0o600);
        assert.equal((await store.release(2, first.token)).ok, false);
        assert.equal((await store.release(1, first.path)).ok, false, 'paths cannot authorize deletion');
        assert.equal((await store.release(1, first.token)).ok, true);
        await assert.rejects(fs.stat(first.path), { code: 'ENOENT' });
        await store.clear(1);
        assert.deepEqual(await fs.readdir(tempDir), []);
    } finally { await fs.rm(tempDir, { recursive: true, force: true }); }
});

test('reject unsafe names, nonbytes, excess byte budgets and release unknown tokens without touching disk', async () => {
    const tempDir = await fs.mkdtemp(path.join(os.tmpdir(), 'awd-drop-test-'));
    try {
        const store = createDroppedFileStore({ tempDir });
        for (const name of ['', '.', '..', '../escape', '/tmp/escape', 'a\\b', 'C:escape', 'a\0b']) {
            assert.equal((await store.stage(1, { name, bytes: new Uint8Array([1]) })).ok, false);
        }
        assert.equal((await store.stage(1, { name: 'x', bytes: 'file:///tmp/secret' })).ok, false);
        assert.equal((await store.stage(1, { name: 'x', bytes: new Uint8Array(MAX_BYTES + 1) })).ok, false);
        assert.deepEqual(await fs.readdir(tempDir), []);
        assert.equal((await store.release(1, '../escape')).ok, false);
    } finally { await fs.rm(tempDir, { recursive: true, force: true }); }
});

test('IPC stages only main-frame callers and clears resources when the window is destroyed', async () => {
    const handlers = {}; initDroppedFileService({ handle: (name, fn) => { handlers[name] = fn } });
    let onDestroyed; const mainFrame = {};
    const sender = { id: 23, mainFrame, once: (_name, fn) => { onDestroyed = fn }, isDestroyed: () => false };
    assert.equal((await handlers['fs:stageDroppedFile']({ sender, senderFrame: {} }, { name: 'x', bytes: new Uint8Array([1]) })).ok, false);
    const staged = await handlers['fs:stageDroppedFile']({ sender, senderFrame: mainFrame }, { name: 'x', bytes: new Uint8Array([1]) });
    assert.equal(staged.ok, true);
    onDestroyed();
    for (let i = 0; i < 50; i++) {
        try { await fs.stat(staged.path); await new Promise(r => setTimeout(r, 5)); }
        catch (e) { if (e.code === 'ENOENT') return; throw e; }
    }
    assert.fail('window destruction must clean the staged file');
});

test('concurrent stages reserve their budget and close during materialization leaves no files', async () => {
    const tempDir = await fs.mkdtemp(path.join(os.tmpdir(), 'awd-drop-test-'));
    try {
        const store = createDroppedFileStore({ tempDir });
        const pending = Array.from({ length: 17 }, () => store.stage(1, { name: 'x', bytes: Uint8Array.of(1) }));
        const closing = store.clear(1);
        const results = await Promise.all(pending);
        await closing;
        assert.equal(results.filter(r => r.ok).length, 16);
        assert.equal(results[16].reason, 'too-large');
        assert.deepEqual(await fs.readdir(tempDir), []);
    } finally { await fs.rm(tempDir, { recursive: true, force: true }); }
});
