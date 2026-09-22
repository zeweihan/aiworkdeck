// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * 音频文件的前端单一判据（dev-board#814）。
 *
 * <p>在此之前这张表在前端只存在于 FileTree.vue 的 isAudioFile 里，旁边一句注释写着
 * 「与后端 MeetingRecordingService 的 AUDIO_EXTENSIONS 白名单保持一致」——靠注释保持一致。
 * 现在前端只此一份，并由 tests/project-home/audio-attachment.test.mjs 与后端那张表逐项对拍。
 *
 * <p>两边判错的方向不同、后果也不同：
 * 前端漏判 → 右键没有「转写音频」、附件不提示，用户以为 AI 听得懂这段录音；
 * 前端多判 → 用户点了转写，后端 registerExisting 回一句「该文件不是音频文件」。
 * 两种都不报错，所以只能靠对拍守。
 */

/** 与 backend MeetingRecordingService.AUDIO_EXTENSIONS 逐项一致。 */
export const AUDIO_EXTENSIONS = [
  'mp3', 'm4a', 'aac', 'wav', 'flac', 'ogg', 'opus', 'amr', 'wma', 'webm',
]

/**
 * 判据与后端 isAudioFileName 同一套路数：认扩展名，不认 fileType。
 *
 * <p>fileType 是客户端自填、原样落库、无校验的（与 ContextItem.fileType 同源问题），
 * 但文件树上的 item.fileType 就是从文件名切出来的，两者在这里同解。
 * 文件夹一律不是音频——一个叫「录音.mp3」的文件夹存在得了。
 */
export function isAudioFile(item) {
  if (!item || item.isFolder || item.isDir) return false
  const ext = extensionOf(item.name) || String(item.fileType || '').toLowerCase()
  return AUDIO_EXTENSIONS.includes(ext)
}

function extensionOf(name) {
  if (!name) return ''
  const dot = String(name).lastIndexOf('.')
  if (dot < 0 || dot === String(name).length - 1) return ''
  return String(name).slice(dot + 1).toLowerCase()
}

/**
 * 本轮附件里「AI 其实读不到」的那些音频：没有转写稿的。
 *
 * @param {Array} contextFiles 输入框上挂着的附件
 * @param {Set|Array} transcribedFileIds 已转写完成的音频 fileId（来自 GET /api/meetings/projects/{id}）
 * @returns {Array} 需要提示的音频附件；有转写稿的不在内——那些 AI 真的读得到
 */
export function audioNeedingTranscription(contextFiles, transcribedFileIds) {
  if (!Array.isArray(contextFiles) || contextFiles.length === 0) return []
  const done = transcribedFileIds instanceof Set
    ? transcribedFileIds
    : new Set(Array.isArray(transcribedFileIds) ? transcribedFileIds : [])
  // id 两边一个是数字一个是字符串是常态（附件 id 经过 HTTP 往返），统一按字符串比
  return contextFiles.filter((f) => isAudioFile(f) && !done.has(String(f.id)))
}

/**
 * 会议列表 → 已转写音频的 fileId 集合。
 *
 * <p>判据必须是 status === 'TRANSCRIBED'：转写中/失败/未识别到人声的记录同样挂着
 * audioFileId，把它们算作「已转写」会让提示消失，而 AI 那边照样读不到正文。
 */
export function transcribedAudioFileIds(meetings) {
  const ids = new Set()
  if (!Array.isArray(meetings)) return ids
  for (const m of meetings) {
    if (m && m.status === 'TRANSCRIBED' && m.audioFileId != null) ids.add(String(m.audioFileId))
  }
  return ids
}
