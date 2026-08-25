/**
 * WAV 录音器纯函数（dev-board#153）：封头正确性与降采样长度。
 * 采集端（getUserMedia/ScriptProcessor）依赖真浏览器，不进单测。
 */
import test from 'node:test'
import assert from 'node:assert/strict'

globalThis.btoa = globalThis.btoa || ((s) => Buffer.from(s, 'binary').toString('base64'))

const { downsample, encodeWavBase64 } = await import('./wavRecorder.js')

test('encodeWavBase64：RIFF/WAVE 头、16k 采样率、数据长度都对', () => {
  const samples = new Float32Array(1600).fill(0.5)
  const bytes = Buffer.from(encodeWavBase64(samples, 16000), 'base64')
  assert.equal(bytes.length, 44 + 1600 * 2)
  assert.equal(bytes.subarray(0, 4).toString('ascii'), 'RIFF')
  assert.equal(bytes.subarray(8, 12).toString('ascii'), 'WAVE')
  assert.equal(bytes.readUInt32LE(24), 16000, '采样率字段')
  assert.equal(bytes.readUInt16LE(22), 1, '单声道')
  assert.equal(bytes.readUInt16LE(34), 16, '16bit')
  assert.equal(bytes.readUInt32LE(40), 1600 * 2, 'data 段长度')
  // 0.5 幅度 → 约 16383
  assert.ok(Math.abs(bytes.readInt16LE(44) - 16383) <= 1)
})

test('downsample：48k→16k 长度缩为 1/3，均值窗不炸幅', () => {
  const input = new Float32Array(48000).fill(0.25)
  const out = downsample(input, 48000, 16000)
  assert.equal(out.length, 16000)
  assert.ok(Math.abs(out[8000] - 0.25) < 1e-6)
  // 同采样率原样返回
  assert.equal(downsample(input, 16000, 16000), input)
})
