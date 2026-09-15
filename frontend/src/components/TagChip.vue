<!-- SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->
<template>
  <view 
    class="tag-chip" 
    :style="{ backgroundColor: tag.color + '20', color: tag.color, borderColor: tag.color + '40' }"
    @click.stop="$emit('click')"
  >
    <text class="tag-name">{{ tag.name }}</text>
    <view v-if="closable" class="tag-close" @click.stop="$emit('close')">×</view>
  </view>
</template>

<script>
export default {
  props: {
    tag: {
      type: Object,
      required: true
    },
    closable: {
      type: Boolean,
      default: false
    }
  }
}
</script>

<style scoped>
.tag-chip {
  display: inline-flex;
  align-items: center;
  padding: 2px 8px;
  border-radius: 12px;
  border: 1px solid transparent;
  font-size: 11px;
  margin-right: 4px;
  margin-bottom: 2px;
  height: 20px;
  line-height: normal;
  transition: all 0.2s;
}

.tag-name {
  font-weight: 500;
}

.tag-close {
  margin-left: 4px;
  font-size: 14px;
  font-weight: bold;
  cursor: pointer;
  opacity: 0.6;
}

.tag-close:hover {
  opacity: 1;
}
</style>
