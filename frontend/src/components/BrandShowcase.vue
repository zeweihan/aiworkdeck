<!-- SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->
<template>
  <!-- 登录页左栏（桌面解锁页与云端浏览器登录页共用）。纯展示，没有任何可点的东西。
       文案全部来自 onboarding.unlock.brand.*，其值与 design/copy/brand-copy.json 逐字相等
       （scripts/check-brand-copy.mjs 对拍），这里不许再写任何一句宣传语。 -->
  <view class="brand-showcase" :class="{ 'is-en': en }">
    <view class="bs-stage" :style="{ transform: stageTransform }">
      <text class="bs-eyebrow">{{ $t('onboarding.unlock.brand.brand') }} · {{ $t('onboarding.unlock.brand.edition') }}</text>
      <view class="bs-title">
        <text>{{ taglineHead }}</text><text class="bs-title-accent">{{ taglineAccent }}</text>
      </view>
      <text class="bs-lead">{{ $t('onboarding.unlock.brand.lead') }}</text>
      <view class="bs-rule"></view>
      <view class="bs-sources">
        <view v-for="s in sources" :key="s.key" class="bs-source" :class="'tone-' + s.tone">
          <view class="bs-dot"></view>
          <text>{{ s.label }}</text>
        </view>
      </view>
      <text class="bs-caption">{{ $t('onboarding.unlock.brand.sourcesCaption') }}</text>
    </view>
    <!-- 缔约主体按站点给：两站是两个商业实体（双主站设计），不跟界面语言走 -->
    <view class="bs-foot">
      <view class="bs-entity">
        <text class="bs-entity-name">{{ entityName }}</text>
        <text v-if="entityRegion"> · {{ entityRegion }}</text>
      </view>
      <text>{{ versionLine }}</text>
    </view>
  </view>
</template>

<script>
import { host } from '@/services/host.js'

// 十类工作来源，与官网 ConvergenceHero 同名同序（顺序由 check-brand-copy 钉住）。
// tone 是来源类别的色点：文档蓝 / 珊瑚 / 研究淡紫 / 暖金 / 竹月青，与官网 hero 的角色配色同族。
const SOURCES = [
  { key: 'document', tone: 'doc' },
  { key: 'mail', tone: 'bamboo' },
  { key: 'chat', tone: 'bamboo', intlKey: 'chatIntl' },
  { key: 'data', tone: 'gold' },
  { key: 'recorder', tone: 'coral' },
  { key: 'phone', tone: 'coral' },
  { key: 'camera', tone: 'bamboo' },
  { key: 'pdf', tone: 'doc' },
  { key: 'law', tone: 'research' },
  { key: 'folder', tone: 'bamboo' },
]

// 两个站点的缔约主体（法定名称，不翻译）
const ENTITIES = {
  cn: { name: '北京京微资易科技有限公司', region: '' },
  intl: { name: 'Zhen Shan Mei Grace Legacy Limited', regionKey: 'onboarding.unlock.hongKong' },
}

// 视差幅度上限（度）。只是一点「活着」的感觉，大了会晃眼
const MAX_TILT = 2

export default {
  name: 'BrandShowcase',
  props: {
    // 'cn' | 'intl'；未知值按 cn 渲染（内置站点就是 cn）
    site: { type: String, default: 'cn' },
  },
  data() {
    return {
      version: '',
      px: 0.5,
      py: 0.5,
      motionOn: false,
    }
  },
  computed: {
    en() {
      // 读 $i18n.locale 而不是 isEnglish()：后者不是响应式的，解锁页就地切语言后排版类不跟
      return String(this.$i18n.locale || '').startsWith('en')
    },
    isIntl() {
      return this.site === 'intl'
    },
    taglineAccent() {
      return this.$t('onboarding.unlock.brand.taglineAccent')
    },
    taglineHead() {
      const full = this.$t('onboarding.unlock.brand.tagline')
      const accent = this.taglineAccent
      return full.endsWith(accent) ? full.slice(0, full.length - accent.length) : full
    },
    sources() {
      return SOURCES.map((s) => ({
        key: s.key,
        tone: s.tone,
        label: this.$t('onboarding.unlock.brand.sources.' + (this.isIntl && s.intlKey ? s.intlKey : s.key)),
      }))
    },
    entityName() {
      return (this.isIntl ? ENTITIES.intl : ENTITIES.cn).name
    },
    entityRegion() {
      return this.isIntl ? this.$t(ENTITIES.intl.regionKey) : ''
    },
    versionLine() {
      return this.version ? `v${this.version} · AGPL-3.0` : 'AGPL-3.0'
    },
    stageTransform() {
      if (!this.motionOn) return 'none'
      const rotY = (this.px - 0.5) * 2 * MAX_TILT
      const rotX = (0.5 - this.py) * MAX_TILT
      return `perspective(1600px) rotateY(${rotY.toFixed(2)}deg) rotateX(${rotX.toFixed(2)}deg)`
    },
  },
  mounted() {
    this.loadVersion()
    // #ifdef H5
    try {
      this.motionOn = !window.matchMedia('(prefers-reduced-motion: reduce)').matches
    } catch (e) {
      this.motionOn = false
    }
    if (this.motionOn) {
      this.onMove = (e) => {
        const w = window.innerWidth || 1
        const h = window.innerHeight || 1
        this.px = Math.min(Math.max((e.clientX || 0) / w, 0), 1)
        this.py = Math.min(Math.max((e.clientY || 0) / h, 0), 1)
      }
      window.addEventListener('mousemove', this.onMove, { passive: true })
    }
    // #endif
  },
  beforeUnmount() {
    // #ifdef H5
    if (this.onMove) window.removeEventListener('mousemove', this.onMove)
    // #endif
  },
  methods: {
    /** 桌面端取安装版本；浏览器端取不到就只显示许可证。 */
    async loadVersion() {
      try {
        if (!(host.update && host.update.status)) return
        const s = await host.update.status()
        this.version = (s && (s.effectiveVersion || s.appVersion)) || ''
      } catch (e) {
        this.version = ''
      }
    },
  },
}
</script>

<style lang="scss" scoped>
.brand-showcase {
  position: relative;
  height: 100%;
  box-sizing: border-box;
  padding: 112px 64px 56px;
  display: flex;
  flex-direction: column;

  /* 来源色点：从令牌派生，深色主题下随令牌一起变 */
  --bs-tone-doc: color-mix(in srgb, var(--awd-file-word) 55%, var(--awd-text-3));
  --bs-tone-coral: color-mix(in srgb, var(--awd-danger) 75%, var(--awd-surface));
  --bs-tone-research: color-mix(in srgb, var(--awd-file-image) 35%, var(--awd-text-3));
  --bs-tone-gold: color-mix(in srgb, var(--awd-warning) 60%, var(--awd-gold-line));
  --bs-tone-bamboo: var(--awd-bamboo);
}

.bs-stage {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  transform-origin: 30% 50%;
  transition: transform 0.2s ease-out;
}

.bs-eyebrow {
  font-size: 13px;
  line-height: 1;
  font-weight: 500;
  color: var(--awd-text-2);
}

.bs-title {
  margin-top: 22px;
  font-family: var(--awd-font-serif, 'Songti SC', 'Source Han Serif SC', 'Noto Serif SC', Georgia, serif);
  font-size: 40px;
  line-height: 1.3;
  font-weight: 500;
  letter-spacing: 0.01em;
  color: var(--awd-text);
}

.is-en .bs-title {
  font-family: Georgia, 'Times New Roman', serif;
  font-size: 38px;
}

.bs-title-accent {
  color: var(--awd-accent-text);
}

.bs-lead {
  margin-top: 18px;
  max-width: 30em;
  font-size: 15px;
  line-height: 1.7;
  color: var(--awd-text-2);
}

.bs-rule {
  width: 56px;
  height: 1px;
  background: var(--awd-gold-line);
  margin: 30px 0 26px;
}

.bs-sources {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  max-width: 460px;
}

.bs-source {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  padding: 0 11px;
  font-size: 12px;
  line-height: 26px;
  color: var(--awd-text-2);
  border: 1px solid var(--awd-border);
  border-radius: 999px;
  background: var(--awd-glass);
  white-space: nowrap;
}

.bs-dot {
  width: 6px;
  height: 6px;
  border-radius: 2px;
  flex-shrink: 0;
  background: var(--bs-tone-bamboo);
}

.tone-doc .bs-dot { background: var(--bs-tone-doc); }
.tone-coral .bs-dot { background: var(--bs-tone-coral); }
.tone-research .bs-dot { background: var(--bs-tone-research); }
.tone-gold .bs-dot { background: var(--bs-tone-gold); }

.bs-caption {
  margin-top: 16px;
  font-size: 11px;
  line-height: 1;
  letter-spacing: 0.06em;
  color: var(--awd-text-3);
}

.bs-foot {
  position: absolute;
  left: 64px;
  bottom: 40px;
  display: flex;
  flex-direction: column;
  font-size: 11px;
  line-height: 1.7;
  letter-spacing: 0.03em;
  color: var(--awd-text-3);
}

.bs-entity-name {
  color: var(--awd-text-2);
  font-weight: 500;
}

@media (prefers-reduced-motion: reduce) {
  .bs-stage {
    transition: none;
    transform: none !important;
  }
}
</style>
