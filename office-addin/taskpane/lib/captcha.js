/**
 * 人机验证控件（Office 插件任务窗格）。
 *
 * 第三份实现，三边逻辑必须一致，改一边要看另外两边：
 * - 官网 `aiworkdeck_website/components/captcha/Captcha.tsx`（React）
 * - 桌面端 `frontend/src/utils/captcha.js`（uni-app 无框架版）
 * - 本文件（插件任务窗格，Vue 3 + Vite）
 *
 * ## 为什么插件端非要有这个
 * 插件登录是 `任务窗格 → 云后端 /api/auth/account-login/send-code → AwdkLoginService → 官网`。
 * 官网启用人机验证后**不带 token 就是 403**（`app/api/auth/sms-login/send-code/route.ts` 里
 * `verifyCaptcha` 排在 `sendCode` 之前）。在补上本文件之前，插件端从来没渲染过控件、
 * 也从来没带过 token，于是点「获取验证码」永远只能拿到「请先完成安全验证后再试」，
 * 滑块一次都没出现过——用户会以为是「插件这种形态不支持验证码」，其实是根本没接。
 *
 * ## 任务窗格与桌面端的两处不同
 * - **窗格窄**（Word 默认约 320px，用户还能再拖窄），滑条按 300 起、随容器收窄，
 *   不写死桌面端那个 320——超出窗格宽度时阿里云的弹层会横向溢出、滑到一半没地方放。
 * - **切走视图会卸载组件**，控件挂的那个 div 跟着消失。所以对外多给一个 `destroy()`，
 *   由 `onBeforeUnmount` 调用，避免下次回来时阿里云 SDK 还持有已经不在文档里的节点。
 *
 * ## 三条与官网版一致的坑
 * - **阿里云的 `prefix`/`region` 要在脚本加载之前挂到全局 `AliyunCaptchaConfig`**，
 *   传进 `initAliyunCaptcha` 不生效，而且**不报错**。
 * - **token 一次性**，每次取之前先 `refresh()`；不刷新的话「重发验证码」会带上
 *   已核销的那枚，服务端判重复提交（表现为第一次成功、之后每次都失败）。
 * - **必须存下实例**才有 `refresh()` 可调，`getInstance` 回调不能省。
 */

const SCRIPTS = {
  turnstile: 'https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit',
  aliyun: 'https://o.alicdn.com/captcha-frontend/aliyunCaptcha/AliyunCaptcha.js',
}

const loading = new Map()
function loadScript(src) {
  if (loading.has(src)) return loading.get(src)
  const p = new Promise((resolve, reject) => {
    const el = document.createElement('script')
    el.src = src
    el.async = true
    el.onload = resolve
    el.onerror = () => reject(new Error('captcha script load failed: ' + src))
    document.head.appendChild(el)
  })
  loading.set(src, p)
  return p
}

/** 窗格宽度决定滑条宽度：留 32px 给左右内边距，夹在 [220, 300] 之间 */
function slideWidth(holderId) {
  const el = document.getElementById(holderId)
  const avail = (el && el.clientWidth) || (document.body && document.body.clientWidth) || 300
  return Math.max(220, Math.min(300, avail - 32))
}

/**
 * 装配控件。
 *
 * @param {object} config 云后端转发的官网公开配置
 *        （`GET /api/auth/account-login/captcha-config`，匿名端点）
 * @param {string} holderId 页面上一个空 div 的 id，控件挂在里面
 * @returns {Promise<{getToken: () => Promise<string>, destroy: () => void, provider: string}|null>}
 *          未启用（`provider` 为空）时返回 null，调用方据此**跳过**验证码直接发码——
 *          与官网此刻确实不校验是同一个判断。
 */
export async function setupCaptcha(config, holderId) {
  if (!config || !config.provider) return null

  if (config.provider === 'turnstile') {
    await loadScript(SCRIPTS.turnstile)
    const el = document.getElementById(holderId)
    if (!el || !window.turnstile) return null
    const widgetId = window.turnstile.render(el, {
      sitekey: config.siteKey,
      appearance: 'interaction-only',
    })
    return {
      provider: 'turnstile',
      destroy: () => { try { window.turnstile.remove(widgetId) } catch (e) { /* 已卸载时忽略 */ } },
      getToken: () => new Promise((resolve) => {
        try { window.turnstile.reset(widgetId) } catch (e) { /* 未就绪时忽略 */ }
        if (window.turnstile.execute) window.turnstile.execute(widgetId)
        const started = Date.now()
        const tick = () => {
          const token = window.turnstile.getResponse ? window.turnstile.getResponse(widgetId) : ''
          if (token) return resolve(token)
          if (Date.now() - started > 60000) return resolve('')
          setTimeout(tick, 200)
        }
        tick()
      }),
    }
  }

  // 阿里云：必须在 loadScript 之前设全局，脚本读的是加载那一刻的值
  window.AliyunCaptchaConfig = { region: 'cn', prefix: config.prefix }
  await loadScript(SCRIPTS.aliyun)
  if (!window.initAliyunCaptcha) return null

  let pending = null
  let instance = null
  window.initAliyunCaptcha({
    SceneId: config.sceneId,
    mode: 'popup',
    element: '#' + holderId,
    button: '#' + holderId + '-trigger',
    captchaVerifyCallback: async (captchaVerifyParam) => {
      if (pending) { pending(captchaVerifyParam); pending = null }
      // 这里返回 true 只是让弹窗关掉，**不等于放行**——真正的校验是官网服务端
      // 拿这个 param 再向阿里云核一次。
      return { captchaResult: true, bizResult: true }
    },
    onBizResultCallback: () => { /* 业务结果由发码流程处理 */ },
    getInstance: (i) => { instance = i },
    slideStyle: { width: slideWidth(holderId), height: 40 },
    language: 'cn',
    onError: (e) => console.warn('[captcha] 阿里云控件初始化失败:', e),
  })

  return {
    provider: 'aliyun',
    destroy: () => { try { if (instance && instance.destroy) instance.destroy() } catch (e) { /* 已卸载时忽略 */ } },
    getToken: () => new Promise((resolve) => {
      pending = resolve
      // 一次性参数：每次取之前先刷新，否则拿到的是上一枚已核销的
      try { if (instance && instance.refresh) instance.refresh() } catch (e) { /* 未就绪时忽略 */ }
      const trigger = document.getElementById(holderId + '-trigger')
      if (!trigger) return resolve('')
      trigger.click()
      setTimeout(() => { if (pending === resolve) { pending = null; resolve('') } }, 120000)
    }),
  }
}
