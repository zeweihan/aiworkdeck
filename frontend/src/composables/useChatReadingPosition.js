// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
import { ref, watch, nextTick, onBeforeUnmount } from 'vue'

// Observe rendered height, rather than traversing every historical token/tool result.
export function useChatReadingPosition(listRef, contentRef, onViewportChange = () => {}) {
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
    onViewportChange()
  }
  // Jumping and the "is it on screen" test must resolve the same element, or the locator
  // bar can measure one card and scroll to another.
  const locate = ({ index, target = 'turn' }) => {
    const list = element(listRef)
    const row = list?.querySelector(`[data-message-index="${Number(index)}"]`)
    if (!row) return null
    const selector = target === 'answer' ? '[data-chat-answer]' : target === 'attention' ? '[data-chat-attention]' : null
    return { list, destination: (selector && row.querySelector(selector)) || row }
  }
  const navigateToMessage = ({ index, target = 'turn' }) => {
    const found = locate({ index, target })
    if (!found) return null
    followLatest.value = false
    found.list.scrollTop += found.destination.getBoundingClientRect().top - found.list.getBoundingClientRect().top - 12
    lastTop = found.list.scrollTop
    onViewportChange()
    return found.destination
  }
  // Only report "off screen" when it was actually measured: a row that has not rendered
  // yet is not proof the user lost sight of it, and claiming so flashes the bar on every
  // freshly arrived card.
  const isMessageOffscreen = ({ index, target = 'turn' }) => {
    const found = locate({ index, target })
    if (!found) return false
    const view = found.list.getBoundingClientRect()
    const rect = found.destination.getBoundingClientRect()
    return rect.top >= view.bottom || rect.bottom <= view.top
  }
  watch([listRef, contentRef], () => {
    observer?.disconnect()
    const list = element(listRef)
    const content = element(contentRef)
    if (!list || !content) return
    if (typeof ResizeObserver !== 'undefined') {
      observer = new ResizeObserver(() => { if (followLatest.value) scrollToBottom(); onViewportChange() })
      observer.observe(list)
      observer.observe(content)
    }
    if (followLatest.value) scrollToBottom()
  }, { flush: 'post' })
  onBeforeUnmount(() => { disposed = true; observer?.disconnect() })
  return { followLatest, handleMessageScroll, scrollToBottom, navigateToMessage, isMessageOffscreen }
}
