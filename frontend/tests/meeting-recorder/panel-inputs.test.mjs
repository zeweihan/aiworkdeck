import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

// 执行组件本身的方法，设备清单与接口用替身，避免真实麦克风和云端扣费。
const source = readFileSync(new URL('../../src/components/MeetingRecordingPanel.vue', import.meta.url), 'utf8')
const script = source.match(/<script>([\s\S]*?)<\/script>/)[1]
  .replace(/^import[\s\S]*?from ['"][^'"]+['"]\s*$/gm, '')
  .replace('export default', 'return')
function panel() {
  let devices = []
  const updates = []
  const component = new Function('AwdSwitch', 'AwdSelect', 'listAudioInputDevices', 'updateMeetingRecording', 'uni', 'formatSeconds', script)(
    {}, {}, async () => devices, async (...args) => updates.push(args),
    { getStorageSync: () => 'iphone', setStorageSync() {} }, value => String(value),
  )
  const vm = {
    audioDevices: [], selectedDeviceIndex: 0,
    $t: key => key, loadMeetings: async () => {}, refreshOne: async () => {},
    ...component.methods,
  }
  vm.loadMeetings = async () => {}
  vm.refreshOne = async () => {}
  return { vm, updates, setDevices: value => { devices = value } }
}
const defaultMic = { deviceId: 'default', label: 'Default - MacBook Pro Microphone' }
const iphone = { deviceId: 'iphone', label: 'iPhone microphone' }
const usb = { deviceId: 'usb', label: 'USB microphone' }

test('初开优先系统默认麦克风，不恢复旧 iPhone 选择，也不依赖列表顺序', async () => {
  const { vm, setDevices } = panel()
  setDevices([iphone, defaultMic, usb])
  await vm.loadAudioDevices()
  assert.equal(vm.audioDevices[vm.selectedDeviceIndex].deviceId, 'default')
})

test('设备增删重排保留本次选择，选中设备离线后切默认且重新出现不抢占', async () => {
  const { vm, setDevices } = panel()
  setDevices([defaultMic, usb])
  await vm.loadAudioDevices()
  vm.onDeviceChange(1)
  setDevices([iphone, defaultMic, usb])
  await vm.loadAudioDevices()
  assert.equal(vm.audioDevices[vm.selectedDeviceIndex].deviceId, 'usb')
  setDevices([iphone, defaultMic])
  await vm.loadAudioDevices()
  assert.equal(vm.audioDevices[vm.selectedDeviceIndex].deviceId, 'default')
  setDevices([usb, iphone, defaultMic])
  await vm.loadAudioDevices()
  assert.equal(vm.audioDevices[vm.selectedDeviceIndex].deviceId, 'default')
})

test('没有 default 别名时使用首个输入，空列表不会出错', async () => {
  const { vm, setDevices } = panel()
  setDevices([usb, iphone])
  await vm.loadAudioDevices()
  assert.equal(vm.selectedDeviceIndex, 0)
  setDevices([])
  await vm.loadAudioDevices()
  assert.equal(vm.selectedDeviceIndex, 0)
})

test('标题与说话人输入框回车触发保存，保存的是修剪后的输入且不丢其他说话人', async () => {
  assert.ok(/<input\b[^>]*v-model="editingTitle"[^>]*@confirm="saveTitle\(m\)"/.test(source), "标题输入框必须接回车保存")
  assert.ok(/<input\b[^>]*v-model="editingSpeakerName"[^>]*@confirm="saveSpeakerName\(m\)"/.test(source), "说话人输入框必须接回车保存")
  const { vm, updates } = panel()
  const m = { id: 8, speakerNames: '{"1":"原名","2":"李律师"}' }
  vm.editingTitle = '  案件讨论  '
  vm.editingTitleId = 8
  await vm.saveTitle(m)
  assert.deepEqual(updates[0], [8, { title: '案件讨论' }])
  assert.equal(vm.editingTitleId, null)
  vm.editingSpeakerId = '1'
  vm.editingSpeakerName = '  王律师  '
  await vm.saveSpeakerName(m)
  assert.deepEqual(updates[1], [8, { speakerNames: { 1: '王律师', 2: '李律师' } }])
  assert.equal(vm.editingSpeakerId, null)
})


test('存量解析失败记录不再回显原始 JSON，同时保留可操作的一般错误', () => {
  const { vm } = panel()
  assert.equal(vm.transcriptionError({ error: '转写结果处理失败: JSON {"TaskId":"private-task"}' }), 'meeting.resultUnreadable')
  assert.equal(vm.transcriptionError({ error: '请检查网络后重试' }), '请检查网络后重试')
})
