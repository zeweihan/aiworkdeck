// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// Byte-only drop fallback. No caller-supplied source/destination paths and no
// arbitrary deletion: release accepts a per-window opaque token only.
const fs = require('node:fs/promises');
const os = require('node:os');
const path = require('node:path');
const { randomUUID } = require('node:crypto');
const MAX_BYTES = 100 * 1024 * 1024;

function createDroppedFileStore({ tempDir = os.tmpdir() } = {}) {
    const entries = new Map();
    async function release(owner, token) {
        const entry = entries.get(token);
        if (!entry || entry.owner !== owner) return { ok: false };
        await entry.ready;
        if (entry.dir) await fs.rm(entry.dir, { recursive: true, force: true });
        entries.delete(token);
        return { ok: true };
    }
    async function clear(owner) {
        await Promise.all(Array.from(entries, ([token, entry]) => entry.owner === owner ? release(owner, token) : null));
    }
    async function stage(owner, { name, bytes } = {}) {
        if (typeof name !== 'string' || !name || name.length > 255 || /[\\/\x00-\x1f:]/.test(name) || name === '.' || name === '..') return { ok: false };
        if (!(bytes instanceof ArrayBuffer) && !ArrayBuffer.isView(bytes)) return { ok: false };
        const data = Buffer.from(bytes instanceof ArrayBuffer ? bytes : bytes.buffer, bytes.byteOffset || 0, bytes.byteLength);
        const owned = Array.from(entries.values()).filter(entry => entry.owner === owner);
        if (!data.length) return { ok: false };
        if (data.length > MAX_BYTES || owned.length >= 16 || owned.reduce((n, e) => n + e.size, 0) + data.length > MAX_BYTES) return { ok: false, reason: 'too-large' };
        const token = randomUUID();
        // Reserve the budget before the first await, including concurrent IPCs.
        let finish;
        const entry = { owner, size: data.length, dir: null, ready: new Promise(resolve => { finish = resolve; }) };
        entries.set(token, entry);
        try {
            entry.dir = await fs.mkdtemp(path.join(tempDir, 'awd-drop-'));
            const filePath = path.join(entry.dir, name);
            await fs.writeFile(filePath, data, { flag: 'wx', mode: 0o600 });
            return { ok: true, token, path: filePath };
        } catch (_) {
            if (entry.dir) await fs.rm(entry.dir, { recursive: true, force: true });
            entries.delete(token);
            return { ok: false };
        } finally { finish(); }
    }
    return { stage, release, clear };
}

function initDroppedFileService(ipcMain) {
    const store = createDroppedFileStore();
    const owners = new Set();
    ipcMain.handle('fs:stageDroppedFile', async (event, payload) => {
        if (event.senderFrame !== event.sender.mainFrame) return { ok: false };
        const owner = event.sender.id;
        if (!owners.has(owner)) {
            owners.add(owner);
            event.sender.once('destroyed', () => { owners.delete(owner); store.clear(owner).catch(() => {}); });
        }
        const result = await store.stage(owner, payload);
        if (event.sender.isDestroyed()) { await store.clear(owner); return { ok: false }; }
        return result;
    });
    ipcMain.handle('fs:releaseDroppedFile', (event, token) => store.release(event.sender.id, token));
}
module.exports = { createDroppedFileStore, initDroppedFileService, MAX_BYTES };
