<template>
  <view class="plugin-pane">
    <iframe
      v-if="url"
      ref="pluginFrame"
      :src="url"
      class="plugin-iframe"
      frameborder="0"
      @load="onFrameLoad"
    ></iframe>
    <view v-else class="plugin-error">
      <text>无法加载插件：未配置入口地址</text>
    </view>
  </view>
</template>

<script>
export default {
  name: 'PluginPane',
  props: {
    url: {
      type: String,
      default: ''
    },
    pluginId: {
      type: String,
      default: ''
    }
  },
  methods: {
    onFrameLoad() {
      console.log(`Plugin ${this.pluginId} loaded from ${this.url}`);
      // Here we could inject some bridge or send initialization data
    }
  }
}
</script>

<style scoped lang="scss">
.plugin-pane {
  width: 100%;
  height: 100%;
  background-color: $awd-chrome-panel; /* iframe 加载前的底色保持深色，避免白闪 */
  display: flex;
  flex-direction: column;
}

.plugin-iframe {
  flex: 1;
  width: 100%;
  height: 100%;
  border: none;
}

.plugin-error {
  padding: 40px;
  text-align: center;
  color: $awd-text-on-dark-3;
}
</style>
