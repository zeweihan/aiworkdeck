// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
import { ref, watch, nextTick, onBeforeUnmount } from 'vue'

// Observe rendered height, rather than traversing every historical token/tool result.
export function useChatReadingPosition(listRef, contentRef) {
  const followLatest = ref(true)
  let observer
  let disposed = false
  let lastTop = 0
  const element = ref => ref.value?.$el || ref.value
  const scrollToBottom = () => {
    followLatest.value = true
    nextTick(() => {
      if (disposed || !followLatest.value) return
      const list = element(listRef)
      if (list) {
        list.scrollTop = list.scrollHeight
        lastTop = list.scrollTop
      }
    })
  }
  const handleMessageScroll = () => {
    const list = element(listRef)
    if (!list) return
    const distance = list.scrollHeight - list.clientHeight - list.scrollTop
    if (list.scrollTop < lastTop - 1) followLatest.value = false
    else if (distance <= 48) followLatest.value = true
    lastTop = list.scrollTop
  }
  const navigateToMessage = ({ index, target = 'turn' }) => {
    const list = element(listRef)
    const row = list?.querySelector(`[data-message-index="${Number(index)}"]`)
    if (!row) return
    const selector = target === 'answer' ? '[data-chat-answer]' : target === 'attention' ? '[data-chat-attention]' : null
    const destination = (selector && row.querySelector(selector)) || row
    followLatest.value = false
    list.scrollTop += destination.getBoundingClientRect().top - list.getBoundingClientRect().top - 12
    lastTop = list.scrollTop
  }
  watch([listRef, contentRef], () => {
    observer?.disconnect()
    const list = element(listRef)
    const content = element(contentRef)
    if (!list || !content) return
    if (typeof ResizeObserver !== 'undefined') {
      observer = new ResizeObserver(() => { if (followLatest.value) scrollToBottom() })
      observer.observe(list)
      observer.observe(content)
    }
    if (followLatest.value) scrollToBottom()
  }, { flush: 'post' })
  onBeforeUnmount(() => { disposed = true; observer?.disconnect() })
  return { followLatest, handleMessageScroll, scrollToBottom, navigateToMessage }
}
