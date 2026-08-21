// evidenceEvents.js — EvidenceLink 变化的全局事件名（uni.$emit/$on）。
// 载荷 { docFileId, source: 'editor' | 'panel' }：编辑器的 stale 核对与审阅面板的
// 用户动作互相通知对方重拉缓存；source 用来跳过自己发出的那条。
export const EVIDENCE_CHANGED_EVENT = 'awd:evidence-changed'
