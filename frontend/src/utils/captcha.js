/**
 * 人机验证控件（桌面端）。
 *
 * 官网侧的实现在 `aiworkdeck_website/components/captcha/Captcha.tsx`，这里是同一套逻辑
 * 的无框架版——uni-app 页面用不了 React 组件，但两边的时序与坑必须一致，改一边要看另一边。
 *
 * ## 为什么桌面端非要有这个
 * 桌面端登录是 `桌面 → 本机 Java /api/account/login/send-code → AccountService → 官网`。
 * 官网启用人机验证后不带 token 就是 403，而官网**无法区分**「真桌面端的转发」与
 * 「攻击者直接 POST」——放过不带 token 的请求等于那条闸完全失效。所以桌面端必须真的带上。
 *
 * ## 两条与官网版一致的坑
 * - **阿里云的 `prefix`/`region` 要在脚本加载之前挂到全局 `AliyunCaptchaConfig`**，
 *   传进 `initAliyunCaptcha` 不生效，而且**不报错**（2026-08-18 浏览器实测）。
 * - **token 一次性**，每次取之前先 reset；不 reset 的话「重发验证码」会带上已核销的那枚。
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

/**
 * 装配控件。
 *
 * @param {object} config 官网下发的公开配置（`GET /api/account/captcha-config`）
 * @param {string} holderId 页面上一个空 div 的 id，控件挂在里面
 * @returns {Promise<{getToken: () => Promise<string>, provider: string}>}
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
    getInstance: () => {},
    slideStyle: { width: 320, height: 40 },
    language: 'cn',
    onError: (e) => console.warn('[captcha] 阿里云控件初始化失败:', e),
  })

  return {
    provider: 'aliyun',
    getToken: () => new Promise((resolve) => {
      pending = resolve
      const trigger = document.getElementById(holderId + '-trigger')
      if (!trigger) return resolve('')
      trigger.click()
      setTimeout(() => { if (pending === resolve) { pending = null; resolve('') } }, 120000)
    }),
  }
}
