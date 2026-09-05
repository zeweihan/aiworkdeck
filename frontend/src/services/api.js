// 统一的 API 封装层
// 说明：
// - 所有网络请求都应通过这里发起，组件内禁止直接写 URL。
// - 后端基础地址通过环境变量配置，便于本地 / Sealos / 阿里云等环境切换。

// 导入认证工具
import { getAuthHeaders, getSessionId, clearSession } from '@/utils/auth.js'
import { host, isDesktopHost } from '@/services/host.js'
import { t } from '@/i18n'

/**
 * 功能未配置时的统一引导（#18 T7）。
 * 调用方在 catch 到 err.featureNotConfigured 时调用，弹出"去设置"引导而非通用报错。
 * @param {Error} err request() reject 出的错误，带 featureNotConfigured / feature / message
 */
export function promptFeatureNotConfigured(err) {
  const message = (err && err.message) || t('common.featureNotConfigured');
  uni.showModal({
    title: t('common.featureNotConfiguredTitle'),
    content: message,
    confirmText: t('common.goToSettings'),
    cancelText: t('common.later'),
    success: (r) => {
      if (r.confirm) {
        uni.navigateTo({ url: '/pages/admin/admin' });
      }
    },
  });
}

// 默认后端地址：
// - 本地 H5 开发（localhost/127.0.0.1）：自动指向后端 9696
// - 其他环境：可通过 VITE_API_BASE_URL 覆盖；否则使用默认网关地址
// 注意：用户当前环境后端就挂在 checkbahttps 域名下
// 默认后端地址：
// - 本地 H5 开发（localhost/127.0.0.1）：自动指向后端 9696
// - 其他环境：必须通过 VITE_API_BASE_URL 配置
// 注意：生产环境请确保 .env.production 中配置了 VITE_API_BASE_URL
const DEFAULT_API_BASE_URL = '';

// 本地开发环境后端地址
const LOCAL_API_BASE_URL = 'http://localhost:9696';

/**
 * 检测是否为本地开发环境
 * - 检查 window.location.hostname（H5 开发）
 * - 检查 Electron 环境
 * - 检查 Vite 开发模式
 */
function isLocalDevelopment() {
  // 检查浏览器 URL（最可靠的方式）
  try {
    if (typeof window !== 'undefined' && window.location && window.location.hostname) {
      const host = window.location.hostname;
      // localhost 或 127.0.0.1 或局域网 IP
      if (host === 'localhost' || host === '127.0.0.1' || host.startsWith('192.168.') || host.startsWith('10.')) {
        console.log('[API] 检测到本地开发环境 (hostname: ' + host + ')');
        return true;
      }
    }
  } catch (e) {
    console.warn('[API] 检测 hostname 失败:', e);
  }

  // 检查 Vite 开发模式
  try {
    // eslint-disable-next-line no-undef
    if (typeof import.meta !== 'undefined' && import.meta.env) {
      // DEV 可能是布尔值或字符串
      const isDev = import.meta.env.DEV === true || import.meta.env.DEV === 'true' || import.meta.env.MODE === 'development';
      if (isDev) {
        console.log('[API] 检测到 Vite 开发模式');
        return true;
      }
    }
  } catch (e) {
    console.warn('[API] 检测 Vite DEV 环境失败:', e);
  }

  // 检查 Electron 环境（file:// 协议通常表示本地开发）
  try {
    if (typeof window !== 'undefined' && window.location && window.location.protocol === 'file:') {
      console.log('[API] 检测到 file:// 协议（Electron 本地开发）');
      return true;
    }
  } catch (e) {
    // ignore
  }

  console.log('[API] 非本地开发环境，使用远程 API');
  return false;
}

// 缓存 API 基础 URL，避免每次请求都重新计算
let cachedApiBaseUrl = null;

export function getApiBaseUrl() {
  // 如果已经缓存了，直接返回
  if (cachedApiBaseUrl) {
    return cachedApiBaseUrl;
  }

  // 桌面壳注入的后端地址最优先：端口是主进程启动时实际分配的
  // （打包态默认 5269，冲突自动降 5369/5169），不能再靠写死的常量猜
  try {
    const injected = host.apiBaseUrl;
    if (injected) {
      cachedApiBaseUrl = injected;
      console.log('[API] 使用桌面壳注入的 API 地址:', cachedApiBaseUrl);
      return cachedApiBaseUrl;
    }
  } catch (e) {
    // ignore
  }

  // 优先使用环境变量配置
  try {
    // eslint-disable-next-line no-undef
    if (typeof import.meta !== 'undefined' && import.meta.env && import.meta.env.VITE_API_BASE_URL) {
      // eslint-disable-next-line no-undef
      cachedApiBaseUrl = import.meta.env.VITE_API_BASE_URL;
      console.log('[API] 使用环境变量配置的 API 地址:', cachedApiBaseUrl);
      return cachedApiBaseUrl;
    }
  } catch (e) {
    // 如果 import.meta 不可用，忽略错误
  }

  // 直接检查 window.location.hostname（不通过 isLocalDevelopment 函数，确保能正确执行）
  try {
    const hostname = window?.location?.hostname;
    console.log('[API] 当前 hostname:', hostname);
    if (hostname === 'localhost' || hostname === '127.0.0.1') {
      cachedApiBaseUrl = LOCAL_API_BASE_URL;
      console.log('[API] 检测到本地环境，使用本地后端:', cachedApiBaseUrl);
      return cachedApiBaseUrl;
    }
  } catch (e) {
    console.warn('[API] 检测 hostname 失败:', e);
  }

  // 本地开发环境：使用本地后端（避免跨域问题）
  if (isLocalDevelopment()) {
    cachedApiBaseUrl = LOCAL_API_BASE_URL;
    console.log('[API] 检测到本地开发环境，使用本地后端:', cachedApiBaseUrl);
    return cachedApiBaseUrl;
  }

  if (import.meta.env.PROD && !DEFAULT_API_BASE_URL) {
    console.warn('[API] 警告：生产环境未配置 VITE_API_BASE_URL，后续请求可能会失败');
  }

  cachedApiBaseUrl = DEFAULT_API_BASE_URL;
  console.log('[API] 使用默认远程 API 地址:', cachedApiBaseUrl);
  return cachedApiBaseUrl;
}

// 当前是否已经停在登录页。4010 的兜底动作是 reLaunch 登录页，而 reLaunch 会重挂
// 登录页 → onLoad 再打一轮请求；只要那一轮里还有需要会话的接口，就又是一次 4010，
// 「跳登录页」本身成了产生 4010 的原因，浏览器端表现为整页无限刷新。
// 已经在登录页时清掉会话就够了，不必再跳一次。
function isOnLoginPage() {
  try {
    const pages = typeof getCurrentPages === 'function' ? getCurrentPages() : [];
    const current = pages.length ? pages[pages.length - 1] : null;
    const route = (current && (current.route || (current.$page && current.$page.route))) || '';
    return route.indexOf('pages/login/login') !== -1;
  } catch (e) {
    return false;
  }
}

function request(options) {
  const baseUrl = getApiBaseUrl();
  const url = options.url.startsWith('http')
    ? options.url
    : `${baseUrl.replace(/\/$/, '')}/${options.url.replace(/^\//, '')}`;

  // 打印请求信息（用于调试）
  console.log('发起请求:', {
    method: options.method || 'GET',
    url: url,
    baseUrl: baseUrl,
    originalUrl: options.url,
    data: options.data
  })

  // 获取认证头
  let authHeaders = {}
  try {
    authHeaders = getAuthHeaders()
  } catch (e) {
    console.warn('获取认证头失败:', e)
    // 如果获取失败，使用默认 headers
    authHeaders = options.header || {}
  }

  // 合并请求头（确保自定义 header 优先级更高）
  const headers = {
    ...authHeaders,
    ...(options.header || {}),
  }

  return new Promise((resolve, reject) => {
    uni.request({
      ...options,
      url,
      // uni.request expects query params in 'data' for GET requests
      data: (options.method === 'GET' && options.params) ? { ...options.data, ...options.params } : options.data,
      header: headers,
      success(res) {
        const status = res.statusCode || 0;

        // 首先检查HTTP状态码，如果不是200则直接拒绝（网络级别错误）
        if (status !== 200) {
          const message =
            (res.data && (res.data.message || res.data.error)) ||
            t('common.requestFailedWithStatus', { status });
          console.error('HTTP 状态码错误:', {
            statusCode: status,
            message: message,
            data: res.data,
            header: res.header
          })
          reject(new Error(message));
          return;
        }

        // 以 arraybuffer 接收却实际返回 JSON 时（如 TTS 未配置 → {code:4001}），
        // 按 content-type 解码为对象，让下面的统一 {code} 逻辑能识别（#18 T5/T7）。
        if (options.responseType === 'arraybuffer' && res.data && typeof res.data.byteLength === 'number') {
          const h = res.header || {};
          const ct = h['content-type'] || h['Content-Type'] || '';
          if (ct.indexOf('application/json') !== -1) {
            try {
              res.data = JSON.parse(new TextDecoder('utf-8').decode(new Uint8Array(res.data)));
            } catch (e) {
              console.warn('arraybuffer JSON 解码失败:', e);
            }
          }
        }

        // 统一处理后端返回的 { code: 0, data: ... } 或 { code: 1, message: ... } 格式
        if (res.data && typeof res.data.code !== 'undefined') {
          if (res.data.code === 0) {
            // 成功：code=0
            resolve(res.data);
          } else if (res.data.code === 4005) {
            // 密码已对但还差短信验证码（登录二次验证）：reject 时带 smsRequired 标记，
            // 登录页据此切到验证码输入步骤。这不是错误态，不能落进通用报错。
            const err = new Error(res.data.message || t('common.smsVerificationRequired'));
            err.smsRequired = true;
            err.data = res.data.data || {};
            reject(err);
          } else if (res.data.code === 4001) {
            // 功能未配置（#18 T7）：reject 时带 featureNotConfigured 标记，
            // 由调用方决定如何引导（弹"去设置" / 降级为只读），避免在拦截器
            // 层强弹全局弹窗导致打开文档时反复打扰。
            const msg = res.data.message || t('common.featureNotConfigured');
            console.warn('功能未配置:', { feature: res.data.feature, message: msg });
            const err = new Error(msg);
            err.featureNotConfigured = true;
            err.feature = res.data.feature || '';
            reject(err);
          } else if (res.data.code === 4003) {
            // 免费额度已满（PR-C）：功能本身是好的，只是到顶了，下一步是解锁而非去设置。
            // 打上 quotaExceeded 标记让调用方能显示解锁引导而不是通用报错。
            // 注意：这条路径**只拒绝新增**，用户已有的数据一条都没动。
            const msg = res.data.message || t('common.freeQuotaExceeded');
            const err = new Error(msg);
            err.quotaExceeded = true;
            err.feature = res.data.feature || '';
            err.usage = res.data.usage || null;
            reject(err);
          } else {
            // 业务失败：code=1 或其他非0值
            const errorMessage = res.data.message || t('common.serviceErrorRetryLater')
            console.error('业务错误:', {
              code: res.data.code,
              message: errorMessage,
              data: res.data.data,
              fullResponse: res.data
            })

            // 特殊处理：未登录错误（PR4-0：后端统一回 code=4010，只认 code 不再做
            // 「登录/未授权/请先」中文子串匹配——子串会误伤含「请先」的业务文案，
            // 且后端文案英文化后整条判定失效）
            if (res.data.code === 4010) {
              // 清除本地存储的 session 信息（真实 key 是 checkba_session_id / checkba_user，
              // 统一走 auth.js 的 clearSession，避免再清错 key）
              try {
                clearSession();
              } catch (e) {
                console.warn('清除登录信息失败:', e);
              }

              // 桌面端（local-mode 免登录）没有登录页可去：只提示，不打断当前页面
              const isDesktop = isDesktopHost();
              if (isDesktop) {
                uni.showToast({
                  title: errorMessage,
                  icon: 'none',
                  duration: 2000
                });
              } else if (isOnLoginPage()) {
                // 已经在登录页：会话清掉即可，再 reLaunch 一次只会重挂本页并重跑
                // onLoad 的请求，构成自激循环（见 isOnLoginPage 上方注释）
                console.warn('未登录，且已在登录页，跳过跳转');
              } else {
                console.warn('检测到未登录状态，准备跳转到登录页');
                uni.reLaunch({
                  url: '/pages/login/login',
                  success: () => {
                    console.log('已跳转到登录页');
                    uni.showToast({
                      title: t('common.loginExpired'),
                      icon: 'none',
                      duration: 2000
                    });
                  },
                  fail: (err) => {
                    console.error('跳转到登录页失败:', err);
                  }
                });
              }
            }

            // 把 code 附在 err 上：调用方（如 project-list.vue）判 err.code === 4010，
            // 不再对 err.message 做中文子串匹配
            const bizErr = new Error(errorMessage);
            bizErr.code = res.data.code;
            // 账户 SKU 购买失败的机器可读原因（already_owned / insufficient_credits /
            // invalid_sku）：「余额不足」要多摆一个「去充值」按钮，双语 message 子串
            // 判不住，reason 才是判据（后端 AccountController.handleSkuPurchaseException）。
            if (res.data.reason) {
              bizErr.reason = res.data.reason;
            }
            // 平台服务网关的失败分类。**必须原样带上来**：三类故障（未开放 / 上游挂了 /
            // 我们挂了）在用户眼里长得一模一样，下一步却完全不同，而 canUseOwnKey 决定
            // 要不要摆「改用自己的 Key」这个逃生门。丢在这一层等于后端白分了类。
            if (res.data.gatewayKind) {
              bizErr.gatewayKind = res.data.gatewayKind;
              bizErr.canUseOwnKey = res.data.canUseOwnKey !== false;
            }
            reject(bizErr);
          }
        } else {
          // 如果没有 code 字段，直接返回数据（兼容旧接口）
          resolve(res.data);
        }
      },
      fail(err) {
        // 完整打印网络请求失败的错误信息
        console.error('网络请求失败:', err)
        console.error('错误详情:', {
          errMsg: err.errMsg,
          statusCode: err.statusCode,
          data: err.data,
          header: err.header,
          cookies: err.cookies,
          requestUrl: url,
          requestMethod: options.method || 'GET',
          baseUrl: baseUrl,
          originalUrl: options.url
        })
        // 根据 errMsg 提供更准确的诊断信息
        let diagnosticMessage = '网络请求失败，请检查：'
        if (err.errMsg && err.errMsg.includes('request:fail')) {
          diagnosticMessage += '\n1. 后端服务是否正在运行（检查端口 9696）'
          diagnosticMessage += '\n2. 后端服务是否启动成功（检查后端日志 backend/app.log）'
          diagnosticMessage += '\n3. 网络连接是否正常'
          diagnosticMessage += `\n4. 当前配置的 API 地址: ${baseUrl}`
          diagnosticMessage += '\n5. 如果使用内网穿透，请确认隧道是否正常运行'
        } else {
          diagnosticMessage += '\n1. 网络连接问题'
          diagnosticMessage += '\n2. 后端服务异常'
          diagnosticMessage += `\n3. 当前配置的 API 地址: ${baseUrl}`
        }
        console.error('诊断信息:', diagnosticMessage)
        reject(err);
      },
    });
  });
}

// 查询公司基础信息（企查查等外部服务由后端统一封装）
// payload 建议结构：
// {
//   projectType: 'MAJOR_ASSET_RESTRUCTURING',
//   role: 'LISTED' | 'TARGET',
//   name: '公司名称'
// }
//
// 信封已收成标准的 `{code, data|message}`（2026-08-17）：网关失败经
// `GlobalExceptionHandler.handleGateway` 带上 `gatewayKind`（未开放 / 上游挂 / 我们挂 /
// 余额不足）与 `canUseOwnKey`，调用方可以据此给不同文案与不同的下一步。
// 旧写法是 `catch(RuntimeException)` → HTTP 500 + `{error, message}`，这两样信息全丢，
// 三类故障在企业数据这条路上长得一模一样。**别再按中文子串把分类猜回来**——
// api.js 早年那套「登录/未授权/请先」子串判定就是这么误伤业务文案的，PR4-0 已经拆掉。
// 「改用自己的 Key」这个逃生门仍然**不依赖**错误分类：系统管理「平台服务」面板每一行
// 都常驻一个折叠入口，并支持深链 `?nav=platform&service=qichacha` 就地展开那一项。
//
// 注：**这条今天没有 UI 调用方**（只在这里导出）。企业数据实际走的是 AI 工具那条路
// （EnterpriseDataTools 的 qichacha_query）。留着是因为它是那条路的孪生实现，
// 删掉以后要接回来更麻烦；改它时别指望在页面里看到效果。
export function fetchCompanyBasicInfo(payload) {
  return request({
    url: '/api/external/company/basic',
    method: 'POST',
    data: payload,
    header: {
      'Content-Type': 'application/json',
    },
  }).then(unwrapEnvelope);
}

// ===================== AI 助手相关 API =====================

// 注：aiChat（POST /api/ai/chat）已随 v1 同步对话通道一并移除。
// 对话走 SSE：见 /api/agent/connect + /api/agent/chat。

export function getAiHistory(params) {
  return request({
    url: '/api/ai/history',
    method: 'GET',
    params: params
  });
}

export function getAiConversations(projectId) {
  return request({
    url: '/api/ai/conversations',
    method: 'GET',
    params: { projectId }
  });
}

/**
 * 获取对话元数据：文件变动和Token使用量
 * @param {string} conversationId 对话ID
 */
export function getConversationMetadata(conversationId) {
  return request({
    url: `/api/ai/conversation/${conversationId}/metadata`,
    method: 'GET'
  });
}

/**
 * 把一条插件镜像会话（sourceChannel 非空，只读）整体复制成一条可写的本地会话
 * （dev-board#298）。后端返回 {code:0, data:{conversationId}}，这里剥掉信封
 * 直接给 {conversationId}。
 */
export function forkAiConversation(conversationId) {
  return request({
    url: `/api/ai/conversation/${conversationId}/fork`,
    method: 'POST'
  }).then(unwrapEnvelope);
}

/**
 * 执行 PPT 生成
 * payload: { topic, projectId, parentId, fileName, style, language, modelId, conversationId, exportEditable }
 */
export function performPptGeneration(payload) {
  return request({
    url: '/api/agent/ppt/generate',
    method: 'POST',
    data: payload,
    header: {
      'Content-Type': 'application/json',
    },
  });
}

/**
 * 停止一个正在跑的后台任务（PPT 生成等长任务）。
 *
 * 后端 `POST /api/agent/tasks/cancel`，请求体 { conversationId, taskId }；
 * 归属校验按 conversationId（与 GET /api/agent/tasks/active 同口径），
 * 任务已经结束时返回 404「该任务已经结束，无需停止」。
 *
 * **调用方文案只许说「正在停止」**：取消只改任务簿记并广播 background_task_complete，
 * 已经交给 pptx-service 的活儿会继续跑完（cancel(true) 打不断在途的 HTTP 调用）。
 */
export function cancelBackgroundTask(conversationId, taskId) {
  return request({
    url: '/api/agent/tasks/cancel',
    method: 'POST',
    data: { conversationId, taskId },
    header: {
      'Content-Type': 'application/json',
    },
  });
}

// ===================== 插件后台任务（插件规范 v2.4 §11 Jobs） =====================
// 与上面的 Agent 后台任务是两套簿记：这套按项目归属、id 是 ULID、由 PluginJobService 驱动，
// 进度经 SSE client_action `plugin_job_progress` 推送；这三个封装给列表/详情/取消用。
export function listPluginJobs(projectId) {
  return request({
    url: `/api/plugin-jobs?projectId=${encodeURIComponent(projectId)}`,
    method: 'GET',
  });
}

export function getPluginJob(jobId) {
  return request({
    url: `/api/plugin-jobs/${encodeURIComponent(jobId)}`,
    method: 'GET',
  });
}

// 取消只翻标记 + 中断任务线程；任务体要在 checkCancelled 处配合才会真正停下，文案只许说「正在停止」
export function cancelPluginJob(jobId) {
  return request({
    url: `/api/plugin-jobs/${encodeURIComponent(jobId)}/cancel`,
    method: 'POST',
  });
}

// 获取 AI 公共配置（如默认供应商）
export function getAiConfig() {
  return request({
    url: '/api/ai/config',
    method: 'GET'
  });
}

/**
 * 获取当前区域可用的模型目录。
 *
 * 前端**不许再硬编码模型清单**：历史上有三份互不同步的副本（后端 AllowedModels、
 * ChatInterface.vue 的数组、project-overview.vue 的死代码），后果是「后端加了模型
 * 用户看不到、前端写的 id 被工厂静默回落成默认模型」。唯一事实来源是后端白名单。
 *
 * 响应：{ networkRegion, networkRegionMode, networkRegionBasis, defaultModel,
 *        models: [{ id, name, vendor, region, contextLength, vision,
 *                   inputPricePerM, outputPricePerM, tiered }] }
 * models 只含当前网络区域实测可用的模型（境内拿不到国际档，OpenRouter 会返 403 region）。
 *
 * vision（boolean）= 该模型能否直接读图。不支持时后端自动把图片降级成 OCR 转写文本，
 * 前端不需要拦截，但必须在选模型的那一刻就把降级说清楚。**缺字段要当「未知」处理**：
 * 旧后端与拉取失败都会让它是 undefined，当成 false 会对所有模型误报「不支持读图」。
 */
export function fetchAiModels() {
  return request({
    url: '/api/ai/models',
    method: 'GET'
  });
}

/**
 * 本机 Ollama 连通性与模型探测（供应商向导的本地档没有密钥可校验，只能靠探测）。
 *
 * 刻意不收 baseUrl 参数：地址由后端从自己的配置里读。放开等于把后端做成
 * 可以被前端指使的内网探测跳板（云后端与桌面后端共用同一套代码）。
 *
 * 响应恒为 200（SERVICE_DOWN 也是一种正常结论，不是 HTTP 错误）：
 * { status: 'READY' | 'MODEL_MISSING' | 'SERVICE_DOWN', baseUrl, targetModel,
 *   installedModels: string[], message, nextStep, command }
 * command 由后端原样给出（就绪时为 null），前端不要自己拼 `ollama pull`——
 * 目标模型是后端按「入参 > DB > yml」解析的，前端拼的容易和它不一致。
 *
 * @param {string} [model] 可选：探测指定模型（向导里用户还没保存设置就想先试）
 */
export function probeOllama(model) {
  return request({
    url: '/api/ai/ollama/probe',
    method: 'GET',
    data: model ? { model } : {}
  });
}

/**
 * 本机转写（asr-service）就绪探测。与 Ollama 那条同一个范式，理由也相同：
 * 「录音不出本机」这一档没有密钥可校验，只能探。
 *
 * 响应恒为 200：
 * { status: 'READY' | 'MODEL_MISSING' | 'SERVICE_DOWN', baseUrl, model,
 *   diarization: boolean, message, nextStep }
 *
 * **MODEL_MISSING 与 SERVICE_DOWN 必须分开渲染**：前者要下一个 GB 级模型，
 * 后者是重启应用，合并成一句「不可用」等于让律师在录完两小时之后才发现自己没有出路。
 * diarization 从接口读、不在前端写死：本地档没有说话人分离是要写给用户看的取舍。
 */
export function probeLocalAsr() {
  return request({
    url: '/api/asr/local/probe',
    method: 'GET'
  });
}

// 获取插件列表
export function getPlugins() {
  return request({
    url: '/api/plugins/list',
    method: 'GET'
  });
}

// 启用 / 禁用插件（仅管理员）
export function setPluginEnabled(pluginId, enabled) {
  return request({
    url: `/api/plugins/${encodeURIComponent(pluginId)}/${enabled ? 'enable' : 'disable'}`,
    method: 'POST'
  });
}

// 在线插件广场：拉取官网注册表列表（仅含已审核签名的插件）
export function getPluginMarket() {
  return request({
    url: '/api/plugins/market/list',
    method: 'GET'
  });
}

// 安装 / 更新在线插件（仅管理员）。后端会验签 + 逐文件校验哈希，装后默认停用。
export function installMarketPlugin(pluginId) {
  return request({
    url: '/api/plugins/market/install',
    method: 'POST',
    data: { id: pluginId },
    header: { 'Content-Type': 'application/json' },
  });
}

// 卸载在线安装的插件（仅管理员）
export function uninstallMarketPlugin(pluginId) {
  return request({
    url: '/api/plugins/market/uninstall',
    method: 'POST',
    data: { id: pluginId },
    header: { 'Content-Type': 'application/json' },
  });
}

// Web 面板直调本插件的 JAR 工具（规范 v2.5）：登录会话 + 项目写权限 + 工具须为该插件声明；
// 返回 { code, output }，output 是工具的原始字符串输出（通常是 JSON 或 "Error: ..."）
export function invokePluginTool(pluginId, toolName, projectId, args) {
  return request({
    url: `/api/plugins/${pluginId}/tools/${toolName}`,
    method: 'POST',
    data: { projectId, args: args || {} },
    header: { 'Content-Type': 'application/json' },
  });
}

// Web 插件桥 ai.request 的服务端落点（规范 v2.7 P2）：插件经平台 Credits 通道调辅助模型。
// 返回 { code, text, modelId } 或 { code:1, errorCode, message }
export function pluginAiComplete(pluginId, projectId, payload) {
  return request({
    url: `/api/plugins/${pluginId}/ai/complete`,
    method: 'POST',
    data: Object.assign({ projectId }, payload || {}),
    header: { 'Content-Type': 'application/json' },
  });
}

// ==== 声明式贡献点（规范 v2.9 P4）====

// 已启用插件贡献的文书模板清单 -> { code, templates: [{pluginId, id, name, genre, description, fileExt}] }
export function getContributedTemplates() {
  return request({ url: '/api/plugins/contributed/templates', method: 'GET' });
}

// 从贡献模板创建项目文件（登录 + 项目写权限）-> { code, fileId, name }
export function createFileFromContributedTemplate(pluginId, templateId, projectId, parentId, name) {
  return request({
    url: '/api/plugins/contributed/templates/create',
    method: 'POST',
    data: { pluginId, templateId, projectId, parentId, name },
    header: { 'Content-Type': 'application/json' },
  });
}

// 插件设置：声明 + 当前值（secret 只回显尾 4 位）-> { code, settings: [...] }
export function getPluginSettings(pluginId) {
  return request({ url: `/api/plugins/${pluginId}/settings`, method: 'GET' });
}

// 保存插件设置（仅管理员；按声明校验类型）
export function savePluginSettings(pluginId, values) {
  return request({
    url: `/api/plugins/${pluginId}/settings`,
    method: 'POST',
    data: values,
    header: { 'Content-Type': 'application/json' },
  });
}

// 插件贡献的样式画像清单 -> { code, profiles: [{pluginId, id, name, selected}] }
export function getContributedStyleProfiles() {
  return request({ url: '/api/plugins/contributed/style-profiles', method: 'GET' });
}

// 选定/清除全局默认画像（仅管理员）；ref 形如 "<pluginId>:<profileId>"，空 = 清除
export function selectContributedStyleProfile(ref) {
  return request({
    url: '/api/plugins/contributed/style-profiles/select',
    method: 'POST',
    data: { ref: ref || '' },
    header: { 'Content-Type': 'application/json' },
  });
}

// 重新扫描 plugins/ 目录（仅管理员）
export function rescanPlugins() {
  return request({
    url: '/api/plugins/rescan',
    method: 'POST'
  });
}

// 插件开发：在项目「插件开发/<id>/」目录下创建骨架（manifest.json + web/index.html + web/awd-plugin-sdk.js）
export function pluginDevScaffold(projectId, id, name) {
  return request({
    url: '/api/plugins/dev/scaffold',
    method: 'POST',
    data: { projectId, id, name },
    header: { 'Content-Type': 'application/json' },
  });
}

// 插件开发：列出该项目「插件开发」目录下的插件项目及本机安装状态
export function pluginDevStatus(projectId) {
  return request({
    url: '/api/plugins/dev/status',
    method: 'GET',
    params: { projectId },
  });
}

// 插件开发：把某个插件项目装进本机 plugins/ 并热重扫，装完即启用
export function pluginDevInstall(projectId, folderId) {
  return request({
    url: '/api/plugins/dev/install',
    method: 'POST',
    data: { projectId, folderId },
    header: { 'Content-Type': 'application/json' },
  });
}

// 插件开发：卸载本机安装（仅限 dev 来源），不动项目里的源码文件夹
export function pluginDevUninstall(id) {
  return request({
    url: '/api/plugins/dev/uninstall',
    method: 'POST',
    data: { id },
    header: { 'Content-Type': 'application/json' },
  });
}

// 获取 Skill 列表（规范见 docs/SKILL_SPEC.md）
export function getSkills() {
  return request({
    url: '/api/skills/list',
    method: 'GET'
  });
}

// 启用 / 禁用 Skill（仅管理员）
export function setSkillEnabled(skillId, enabled) {
  return request({
    url: `/api/skills/${encodeURIComponent(skillId)}/${enabled ? 'enable' : 'disable'}`,
    method: 'POST'
  });
}

// 设置 Skill 生效方式（仅管理员）：mode = 'auto' | 'manual' | 'disabled'
export function setSkillActivation(skillId, mode) {
  return request({
    url: `/api/skills/${encodeURIComponent(skillId)}/activation`,
    method: 'POST',
    data: { mode },
    header: {
      'Content-Type': 'application/json',
    },
  });
}

// 重新扫描 skills/ 目录（仅管理员）
export function rescanSkills() {
  return request({
    url: '/api/skills/rescan',
    method: 'POST'
  });
}

// 在线 Skill 广场：拉取官网注册表列表（登录即可查看）
export function getSkillMarket() {
  return request({
    url: '/api/skills/market/list',
    method: 'GET'
  });
}

// 安装 / 重装在线 Skill（仅管理员，重装即更新）
export function installMarketSkill(skillId) {
  return request({
    url: '/api/skills/market/install',
    method: 'POST',
    data: { id: skillId },
    header: {
      'Content-Type': 'application/json',
    },
  });
}

// 卸载在线安装的 Skill（仅管理员）
export function uninstallMarketSkill(skillId) {
  return request({
    url: '/api/skills/market/uninstall',
    method: 'POST',
    data: { id: skillId },
    header: {
      'Content-Type': 'application/json',
    },
  });
}

// 原生资源包（native pack）：重资源功能的运行时下载安装，规范见 docs/NATIVE_PACK_DISTRIBUTION.md §4.3

// 查询安装状态（登录即可）：{state, installedVersion, bytesDownloaded, bytesTotal, error}
export function packStatus(packId) {
  return request({
    url: `/api/packs/${encodeURIComponent(packId)}/status`,
    method: 'GET'
  });
}

// 查询最新版本与总体积（登录即可）：{latestVersion, totalSize}，装前的大小提示用
export function packInfo(packId) {
  return request({
    url: `/api/packs/${encodeURIComponent(packId)}/info`,
    method: 'GET'
  });
}

// 安装（仅管理员）：后端异步启动下载，已在装则幂等返回当前进度，前端轮询 packStatus 展示
export function packInstall(packId) {
  return request({
    url: `/api/packs/${encodeURIComponent(packId)}/install`,
    method: 'POST'
  });
}

// 卸载（仅管理员）：删除本机已下载的资源包目录
export function packUninstall(packId) {
  return request({
    url: `/api/packs/${encodeURIComponent(packId)}/uninstall`,
    method: 'POST'
  });
}

/**
 * 将一段 AI 文本（markdown）导出为 Word 文档并落地到项目文件树中（后端生成 docx）
 * payload: { projectId, parentId, fileName, markdown | content }
 */
export function exportAiDocx(payload) {
  return request({
    url: '/api/ai/export-docx',
    method: 'POST',
    data: {
      projectId: String(payload.projectId),
      parentId: payload.parentId,
      fileName: payload.fileName,
      markdown: payload.markdown || payload.content
    },
    header: {
      'Content-Type': 'application/json',
    },
  });
}

/**
 * 回退对话历史到指定消息
 * 删除该消息之后的所有对话记录
 * @param conversationId 对话ID
 * @param messageId 消息ID（回退到此消息之前，此消息也会被删除）
 */
export function rollbackConversation(conversationId, messageId) {
  return request({
    url: '/api/agent/history/rollback',
    method: 'POST',
    data: {
      conversationId,
      messageId
    },
    header: {
      'Content-Type': 'application/json',
    },
  });
}

// ===================== 后台管理相关 API =====================

// 获取后台配置（外部服务 + AI 配置）
export function getAdminConfig() {
  return request({
    url: '/api/admin/config',
    method: 'GET',
  });
}

// 保存后台配置
export function saveAdminConfig(payload) {
  return request({
    url: '/api/admin/config',
    method: 'POST',
    data: payload,
    header: {
      'Content-Type': 'application/json',
    },
  });
}

// 获取用户列表（仅管理员）
export function getAdminUsers() {
  return request({
    url: '/api/admin/users',
    method: 'GET',
  });
}

// 记录《服务条款》《隐私政策》的同意版本（登录页勾选后调用，只记录不设闸）
export function acceptLegalAgreement(version) {
  return request({
    url: '/api/license/agreement',
    method: 'POST',
    data: { version },
    header: {
      'Content-Type': 'application/json',
    },
  });
}

// 查询首启初始化状态：{ code, initialized }。向导页已下线（2026-08-27），
// 这条现在由解锁页在登录成功后查询，决定要不要做一次性初始化提交
export function getWizardStatus() {
  return request({
    url: '/api/admin/wizard',
    method: 'GET',
  });
}

// 提交首启初始化（仅未初始化时可调用，payload 结构同 saveAdminConfig；
// 向导页下线后由解锁页在登录成功后调用；后端 /api/admin/wizard/reset 保留但前端已无入口）
export function submitWizard(payload) {
  return request({
    url: '/api/admin/wizard',
    method: 'POST',
    data: payload,
    header: {
      'Content-Type': 'application/json',
    },
  });
}

// 创建项目接口
// payload: 项目创建请求数据
export function createProject(payload) {
  return request({
    url: '/api/projects',
    method: 'POST',
    data: payload,
    header: {
      'Content-Type': 'application/json',
    },
  });
}

// IDE 化本地文件夹项目：打开/新建本地文件夹作为项目
// payload: { localRoot, createFolder, name, openFileName }
// 返回 { code: 0, data: { projectId, name, reused, openFileId, importedCount, truncated, truncatedCount } }
export function openLocalProject(payload) {
  return request({
    url: '/api/projects/open-local',
    method: 'POST',
    data: payload,
    header: {
      'Content-Type': 'application/json',
    },
  });
}

// 项目物理根目录（「在 Finder 中显示」）
export function getProjectLocalPath(projectId) {
  return request({
    url: `/api/projects/${projectId}/local-path`,
    method: 'GET',
  });
}

// 文件物理路径（「在 Finder 中显示」）
export function getFileLocalPath(fileId) {
  return request({
    url: `/api/files/${fileId}/local-path`,
    method: 'GET',
  });
}

// ===================== 授权（解锁门）相关 API =====================

// 查询本机授权状态：{ unlocked, mode: 'none'|'trial'|'account', plan,
//                    accountConnected, edition: 'paid'|'trial'|'none' }
// 界面上一律读 edition：mode 只是授权票据，先用试用码解锁、后连账户的用户 mode 永远是 trial。
// 旧后端没有 edition/accountConnected 两字段，调用方要各自兜底。
export function getLicenseStatus() {
  return request({
    url: '/api/license/status',
    method: 'GET',
  });
}

// 激活：试用码（离线验签）或账户 Key（在线校验）
// 成功 200 { unlocked: true, mode }；失败 400 { message }（request 层已转成 reject）
export function activateLicense(code) {
  return request({
    url: '/api/license/activate',
    method: 'POST',
    data: { code },
    header: {
      'Content-Type': 'application/json',
    },
  });
}

// 当前站点与可选站点（双主站）。
// 返回 { current, pinned, multiSite, sites: [{ id, displayName, baseUrl, accountPageUrl }] }
export function getSiteStatus() {
  return request({
    url: '/api/site',
    method: 'GET',
  });
}

// 切换站点。**破坏性动作**：会清掉旧站的账户连接、权益缓存、平台 AI 密钥，
// 以及 account 模式的授权票据（试用码票据保留）。调用方必须先做二次确认。
// 成功 200 { site, changed, licenseCleared, accountCleared, restartRecommended }；
// 失败 400 { message }（request 层已转成 reject）
export function selectSite(site) {
  return request({
    url: '/api/site/select',
    method: 'POST',
    data: { site },
    header: {
      'Content-Type': 'application/json',
    },
  });
}

// 解除授权：回到未解锁状态
export function deactivateLicense() {
  return request({
    url: '/api/license/deactivate',
    method: 'POST',
  });
}

// ===================== 插件访问令牌（awdt_ 设备令牌）相关 API =====================
//
// 供 Microsoft Office 插件等外部客户端连接本机后端。桌面端单机免登、本机用户没有口令，
// 走不了 /api/auth/device-token 那条账号口令路，改用 issue-local 拿当前本机会话换令牌
// （该端点仅在 local-mode 存在，团队服务器仍只有账号口令一条路）。

// 签发：{ code: 0, data: { tokenId, token, userId, username, displayName } }
// token 明文只在这一次返回，之后无从再取。
export function issueLocalDeviceToken(name) {
  return request({
    url: '/api/auth/device-token/issue-local',
    method: 'POST',
    data: { name: name || '' },
    header: {
      'Content-Type': 'application/json',
    },
  });
}

// 列表：{ code: 0, data: { tokens: [{ id, name, createdAt, lastUsedAt }] } }
export function listDeviceTokens() {
  return request({
    url: '/api/auth/device-tokens',
    method: 'GET',
  });
}

// 撤销：撤销后持该令牌的客户端立即失去访问权
export function revokeDeviceToken(id) {
  return request({
    url: `/api/auth/device-token/${id}/revoke`,
    method: 'POST',
  });
}

// ===================== 本机工作区（免登身份）相关 API =====================
//
// 单机免登下所有请求都解析为「本机用户」。老安装的库里往往不止一个账号
// （admin 是系统播的空壳，真实数据在用户自己注册的账号名下），后端不再靠猜，
// 多个账号都有数据时返回 needsSelection，由 launch 页分流到选择页。
// 与解锁门同批，返回裸 JSON。

// { localMode, needsSelection, userId, username, displayName }
export function getLocalIdentityStatus() {
  return request({
    url: '/api/local-identity/status',
    method: 'GET',
  });
}

// { localMode, needsSelection, currentUserId, candidates: [{ userId, username, displayName, projectCount, fileCount }] }
// 候选按数据量降序；待选定时 currentUserId 为 null（临时落点不算「已选中」）。
export function getLocalIdentityCandidates() {
  return request({
    url: '/api/local-identity/candidates',
    method: 'GET',
  });
}

// 选定本机工作区。成功 200 { userId }；非法 userId 走 400 { message }（request 层转 reject）
export function selectLocalIdentity(userId) {
  return request({
    url: '/api/local-identity/select',
    method: 'POST',
    data: { userId },
    header: {
      'Content-Type': 'application/json',
    },
  });
}

// ===================== 账户与用量（商业化 PR-B）相关 API =====================
//
// 桌面后端把官网账户接口收敛成本机端点，前端一律只跟本机后端说话
// （awdk_ Key 与 Bearer 鉴权都留在后端，不进渲染进程）。
//
// 信封说明：这批端点与解锁门同批，按 LicenseController 惯例返回裸 JSON；
// 但本仓另有 { code, data } 信封惯例，这里统一剥一层，两种形状都能用。
function unwrapEnvelope(res) {
  if (res && typeof res === 'object' && 'code' in res && 'data' in res) return res.data;
  return res;
}

// 账户连接状态：{ connected, username, displayName, connectedAt, lastSyncAt, keyMasked, platformAiAvailable }
// 未连接时只有 { connected: false, platformAiAvailable: false }。
// 刻意**不含余额与套餐**：这是纯本地读盘的轻端点（顶栏 chip 每次 onShow 都调），
// 带上余额就意味着每次都要打一次官网。余额在 getAccountUsage() 的 platform 段。
export function getAccountStatus() {
  return request({
    url: '/api/account/status',
    method: 'GET',
  }).then(unwrapEnvelope);
}

// 连接账户：粘贴官网账户页生成的 awdk_ Key
export function connectAccount(key) {
  return request({
    url: '/api/account/connect',
    method: 'POST',
    data: { key },
    header: {
      'Content-Type': 'application/json',
    },
  }).then(unwrapEnvelope);
}

// 账户登录：给手机号发验证码（本机后端转发官网）。
// 与 connectAccount 的关系：两条路殊途同归，都是让本机持有一枚 awdk_ Key。
// connectAccount 收用户手工粘贴的 Key（团队服务器与私有部署仍要用），
// 这条与 loginAccount 让用户直接用手机号登录，Key 由官网签发、本机保存，用户看不到它。
export function sendAccountLoginCode(identifier, captchaToken, isPhone = true) {
  return request({
    url: '/api/account/login/send-code',
    method: 'POST',
    // captchaToken 必须一路传到官网：官网启用人机验证后不带就是 403，
    // 而它无法区分「桌面端转发」与「攻击者直接 POST」，所以不存在「桌面端豁免」这条路。
    // 字段名按站点分：cn 是 phone，intl 是 email，本机后端据此选转发目标。
    data: isPhone
      ? { phone: identifier, captchaToken: captchaToken || '' }
      : { email: identifier, captchaToken: captchaToken || '' },
    header: {
      'Content-Type': 'application/json',
    },
  }).then(unwrapEnvelope);
}

// 官网人机验证的公开配置（只有公开参数，没有密钥）。未启用时 provider 为空，
// 调用方据此跳过控件直接发码——与官网此刻确实不校验是同一个判断。
export function getAccountCaptchaConfig() {
  return request({ url: '/api/account/captcha-config', method: 'GET' }).then(unwrapEnvelope);
}

// 账户登录：{ phone, code }（大陆站）或 { account, password }（国际站）。
// 成功后本机已连接账户，返回体同 getAccountStatus 再加 isNewUser / mustBindPhone。
export function loginAccount(payload) {
  return request({
    url: '/api/account/login',
    method: 'POST',
    data: payload,
    header: {
      'Content-Type': 'application/json',
    },
  }).then(unwrapEnvelope);
}

// 断开账户连接：清除本机保存的账户 Key
export function disconnectAccount() {
  return request({
    url: '/api/account/disconnect',
    method: 'POST',
  }).then(unwrapEnvelope);
}

// 用量：两套口径分开返回（Spec §3），前端不做合并。
// {
//   local:    { records, promptTokens, completionTokens, totalTokens,
//               platformCostUsd, estimatedCostUsd,
//               recent: [{ model, createdAt, totalTokens, cost, costSource }] },
//   platform: { connected, available, message?,
//               balanceCents, plan, allocations: [...],
//               quotaAvailable, quotaMessage?, hasAiQuota, limitUsd, usageUsd, remainingUsd }
// }
// platform.available=false 表示官网不可达（本地统计仍然有效）；
// quotaAvailable=false 表示额度实时口径拿不到——此时不要把 0 当成真实剩余额度。
export function getAccountUsage() {
  return request({
    url: '/api/account/usage',
    method: 'GET',
  }).then(unwrapEnvelope);
}

// ===================== 余额 / 会员 / 充值（dev-board#183/#184/#187）=====================

// 顶栏 Credits chip 的轻端点（后端带 TTL 缓存，可随 onShow 高频调）：
// { connected, balanceCents, plan, membership: { level, key, nameZh, nameEn } | null }
// 未连接 { connected: false }；已连接但官网不可达 { connected: true, available: false }。
export function getAccountBalance() {
  return request({
    url: '/api/account/balance',
    method: 'GET',
  }).then(unwrapEnvelope);
}

// 会员等级/成长值全量（官网透传，不缓存）：
// { growthPoints, topupCents, spendCents,
//   tier: { key, level, nameZh, nameEn, bonusPermille },
//   nextTier: { ..., threshold, remainingPoints } | null,
//   tiers: [7 档全表，含 threshold/bonusPermille] }
export function getAccountMembership() {
  return request({
    url: '/api/account/membership',
    method: 'GET',
  }).then(unwrapEnvelope);
}

// 发起充值：amountCents 单位「分」。响应
// { success, present: 'qrcode' | 'redirect', outTradeNo, codeUrl?, qrCode?, redirectUrl?, amount }
// 微信站二维码 / Stripe 站跳转，两种形状由 RechargeDialog 分叉。
export function createAccountRecharge(amountCents) {
  return request({
    url: '/api/account/recharge',
    method: 'POST',
    data: { amountCents },
    header: {
      'Content-Type': 'application/json',
    },
  }).then(unwrapEnvelope);
}

// 查询充值订单状态（轮询用）。已支付形如 { success: true, order: { ..., status: 'paid' } }，
// 未付 order.status 为 pending 等，字段以官网为准。
export function getRechargeStatus(outTradeNo) {
  return request({
    url: '/api/account/recharge/status?outTradeNo=' + encodeURIComponent(outTradeNo || ''),
    method: 'GET',
  }).then(unwrapEnvelope);
}

// 应用内购买本地 SKU（白名单只有 feature:clipboard.unlimited / feature:stage.unlimited）。
// 成功 { ok, feature, balanceCents }，后端已同步刷新权益并作废余额缓存；
// 失败 reject 的 Error 上带 reason（already_owned / insufficient_credits / invalid_sku）。
export function purchaseFeatureSku(skuId) {
  return request({
    url: '/api/account/purchase-sku',
    method: 'POST',
    data: { skuId },
    header: {
      'Content-Type': 'application/json',
    },
  }).then(unwrapEnvelope);
}

// 功能权益：{ features: [...已拥有...], catalog: [...目录全集含 enabled...],
//            accountConnected, syncedAt, stale }
// features 只含已拥有项——「出现在 features 里 = 已拥有」，catalog 才是带 enabled 的全集。
// 由 useEntitlement composable 统一消费，业务代码不要直接调这个。
// refresh=true 让后端先同步一次官网再出快照（连接账户后 / 官网购买后回来时用），
// 默认走后端本地缓存，避免每个页面打开都打一次官网。
export function getEntitlements(refresh = false) {
  return request({
    url: refresh ? '/api/entitlements?refresh=true' : '/api/entitlements',
    method: 'GET',
  }).then(unwrapEnvelope);
}

// ===================== 平台服务（外部服务的档位）=====================

// 八项外部服务各自走哪一档：
// { services: [{ service, provider, hasLocal, hasByokCredentials }],
//   platformAvailable, accountConnected, budget }
//
// provider ∈ platform | byok | local，是**后端解析后的生效值**，不是设置里存的原始值
// （非 local-mode 下即使库里写着 platform 也回 byok，闸在 ExternalProviderResolver 一处）。
// 界面展示当前档位一律读它，不要自己按凭证是否为空去猜。
//
// platformAvailable=false 表示这台机器不是个人桌面版（团队服务器 / 云端实例），
// 平台档在界面上必须不可选并给出说明（设计决策 D5）。
//
// **这条不发任何出站请求**，所以官网挂着的时候它照样秒回——而那正是用户最需要进这一页
// 把档位切成自备 Key 的时候。开放状态/余额/用量在 getPlatformServiceRemote()，单独取。
export function getPlatformServices() {
  return request({
    url: '/api/platform-services',
    method: 'GET',
  }).then(unwrapEnvelope);
}

// 面板的远端那一半：{ pricingAvailable, enabled: {service: bool}, balanceCents,
// pendingHoldCents, usage }。慢、可能取不到，所以**页面渲染完之后再取**，
// 到了才填、没到就是「—」。失败时后端也回 code=0 + 一个「什么都不知道」的载荷。
export function getPlatformServiceRemote() {
  return request({
    url: '/api/platform-services/remote',
    method: 'GET',
  }).then(unwrapEnvelope);
}

// 切档。provider ∈ platform | byok | local；取值非法或该形态不允许时后端回 code=1 +
// 可读 message（**不会**回 4010，那会被判成掉线并清会话）。
export function setPlatformServiceProvider(service, provider) {
  return request({
    url: `/api/platform-services/${encodeURIComponent(service)}/provider`,
    method: 'POST',
    data: { provider },
    header: {
      'Content-Type': 'application/json',
    },
  }).then(unwrapEnvelope);
}

// 花费闸门的两个阈值（分）：单次任务上限、余额低于多少时提醒。0 = 不启用。
// 与档位同属机器级设置，同一道 MachineAccountGuard 把关，所以走同一个控制器——
// 放进 AdminConfigController 会撞上它有意跳过 null 字段的行为，而「0 = 关闭」
// 正是最容易被那条规则吃掉的形状。
export function savePlatformBudget(taskLimitCents, lowBalanceCents) {
  return request({
    url: '/api/platform-services/budget',
    method: 'POST',
    data: { taskLimitCents, lowBalanceCents },
    header: {
      'Content-Type': 'application/json',
    },
  }).then(unwrapEnvelope);
}

// 平台档会议转写的单独告知：{ version, body, acknowledged, acknowledgedAt }。
// 正文由服务端给，**不在前端硬编码**——文本与版本号分开放必然出现「改了文案忘了推版本」，
// 那时全体用户的旧确认会覆盖到他们从没看过的新处理方式。
export function getMeetingAsrNotice() {
  return request({
    url: '/api/platform-services/asr-notice',
    method: 'GET',
  }).then(unwrapEnvelope);
}

// 确认（或撤回）告知。每台机器一次，绝不预勾选——预先勾选的同意在个保法下无效。
export function acknowledgeMeetingAsrNotice(acknowledged) {
  return request({
    url: '/api/platform-services/asr-notice',
    method: 'POST',
    data: { acknowledged: !!acknowledged },
    header: {
      'Content-Type': 'application/json',
    },
  }).then(unwrapEnvelope);
}

// ===================== 用户认证相关 API =====================

// 用户注册
export function register(username, password, displayName) {
  return request({
    url: '/api/auth/register',
    method: 'POST',
    data: {
      username,
      password,
      displayName,
    },
    header: {
      'Content-Type': 'application/json',
    },
  });
}

// 用户登录
export function login(username, password, smsCode) {
  return request({
    url: '/api/auth/login',
    method: 'POST',
    data: {
      username,
      password,
      ...(smsCode ? { smsCode } : {}),
    },
    header: {
      'Content-Type': 'application/json',
    },
  });
}

// 发送短信验证码。scene='login' 需带 username/password（发往已绑定手机号）；
// scene='bind' 需已登录，带 phone（发往待绑定的新手机号）。
export function sendSmsCode(payload) {
  return request({
    url: '/api/auth/sms/send-code',
    method: 'POST',
    data: payload,
    header: {
      'Content-Type': 'application/json',
    },
  });
}

// 绑定/更换手机号（验证码走 sendSmsCode 的 bind 场景）
export function bindPhone(phone, code) {
  return request({
    url: '/api/auth/sms/bind',
    method: 'POST',
    data: { phone, code },
    header: {
      'Content-Type': 'application/json',
    },
  });
}

// 发送邮箱验证码。与 sendSmsCode 逐一对称：scene='login' 需带 username/password
// （发往已绑定邮箱）；scene='bind' 需已登录，带 email（发往待绑定的新邮箱）。
export function sendMailCode(payload) {
  return request({
    url: '/api/auth/mail/send-code',
    method: 'POST',
    data: payload,
    header: {
      'Content-Type': 'application/json',
    },
  });
}

// 绑定/更换邮箱（验证码走 sendMailCode 的 bind 场景）
export function bindEmail(email, code) {
  return request({
    url: '/api/auth/mail/bind',
    method: 'POST',
    data: { email, code },
    header: {
      'Content-Type': 'application/json',
    },
  });
}

// 邮箱免密登录第一步：发码。后端对「已注册」和「未注册」回同一个结果
// （防账号枚举），所以这里成功也不代表该邮箱有账号。
export function mailLoginSendCode(email) {
  return request({
    url: '/api/auth/mail-login/send-code',
    method: 'POST',
    data: { email },
    header: {
      'Content-Type': 'application/json',
    },
  });
}

// 邮箱免密登录第二步：验码换会话，回包结构与密码登录一致
export function mailLoginVerify(email, code) {
  return request({
    url: '/api/auth/mail-login/verify',
    method: 'POST',
    data: { email, code },
    header: {
      'Content-Type': 'application/json',
    },
  });
}

// 认证器（TOTP）：开始绑定，返回 { secret, provisioningUri }。此时尚未生效
export function totpSetup() {
  return request({ url: '/api/auth/totp/setup', method: 'POST' });
}

// 认证器：验一次码完成绑定
export function totpActivate(code) {
  return request({
    url: '/api/auth/totp/activate',
    method: 'POST',
    data: { code },
    header: { 'Content-Type': 'application/json' },
  });
}

// 认证器：解绑（必须带当前码）
export function totpDisable(code) {
  return request({
    url: '/api/auth/totp/disable',
    method: 'POST',
    data: { code },
    header: { 'Content-Type': 'application/json' },
  });
}

export function clientLogin(accessCode, displayName) {
  return request({
    url: '/api/auth/client-login',
    method: 'POST',
    data: { accessCode, displayName },
    header: { 'Content-Type': 'application/json' }
  })
}

export function inviteClient(projectId, clientName) {
  return request({
    url: `/api/projects/${projectId}/invite/client`,
    method: 'POST',
    data: { clientName },
    header: { 'Content-Type': 'application/json' }
  })
}

// 获取当前登录用户信息
export function getCurrentUser() {
  return request({
    url: '/api/auth/me',
    method: 'GET',
  });
}

// 上传用户头像
export function uploadAvatar(filePath) {
  const baseUrl = getApiBaseUrl()
  const url = `${baseUrl}/api/users/avatar`
  const sessionId = getSessionId()

  return new Promise((resolve, reject) => {
    uni.uploadFile({
      url: url,
      filePath: filePath,
      name: 'file',
      header: {
        'X-Session-Id': sessionId
      },
      success: (uploadFileRes) => {
        if (uploadFileRes.statusCode === 200) {
          try {
            const data = JSON.parse(uploadFileRes.data)
            if (data.code === 0) {
              resolve(data)
            } else {
              reject(new Error(data.message || t('common.uploadFailed')))
            }
          } catch (e) {
            reject(new Error(t('common.parseResponseFailed')))
          }
        } else {
          reject(new Error('HTTP Error ' + uploadFileRes.statusCode))
        }
      },
      fail: (err) => {
        reject(err)
      }
    })
  })
}

// 用户登出
export function logout() {
  return request({
    url: '/api/auth/logout',
    method: 'POST',
  });
}

// 获取当前用户的项目列表
export function getMyProjects() {
  return request({
    url: '/api/projects/my',
    method: 'GET',
  });
}

// 删除项目
export function deleteProject(projectId) {
  return request({
    url: `/api/projects/${projectId}`,
    method: 'DELETE',
  });
}

// 获取项目详情
export function getProject(projectId) {
  return request({
    url: `/api/projects/${projectId}`,
    method: 'GET',
  });
}

// 重命名项目
export function renameProject(projectId, name) {
  return request({
    url: `/api/projects/${projectId}`,
    method: 'PUT',
    data: { name },
    header: {
      'Content-Type': 'application/json',
    },
  });
}

// ===================== 项目文件管理相关 API =====================

// 获取项目文件列表
export function getProjectFiles(projectId, parentId = null, tree = false) {
  const params = []
  if (parentId !== null) {
    params.push(`parentId=${parentId}`)
  }
  if (tree) {
    params.push('tree=true')
  }
  const queryString = params.length > 0 ? `?${params.join('&')}` : ''
  return request({
    url: `/api/projects/${projectId}/files${queryString}`,
    method: 'GET',
  });
}

// 创建文件夹
export function createFolder(projectId, parentId, name) {
  return request({
    url: `/api/projects/${projectId}/files/folder`,
    method: 'POST',
    data: {
      parentId,
      name,
    },
    header: {
      'Content-Type': 'application/json',
    },
  });
}

// 压缩包（zip/7z/rar）条目列表（预览）
export function getArchiveEntries(projectId, fileId) {
  return request({
    url: `/api/projects/${projectId}/files/${fileId}/archive/entries`,
    method: 'GET',
  });
}

// 解压压缩包到其所在目录下的新文件夹，返回新建的根文件夹记录
export function extractArchive(projectId, fileId) {
  return request({
    url: `/api/projects/${projectId}/files/${fileId}/archive/extract`,
    method: 'POST',
    header: {
      'Content-Type': 'application/json',
    },
  });
}

// 创建文件
export function createFile(projectId, parentId, name, fileType, fileSize, filePath, wpsFileId) {
  return request({
    url: `/api/projects/${projectId}/files/file`,
    method: 'POST',
    data: {
      parentId,
      name,
      fileType,
      fileSize,
      filePath,
      wpsFileId,
    },
    header: {
      'Content-Type': 'application/json',
    },
  });
}

// 从本机绝对路径复制一个文件进项目目录（桌面端拖入资源管理器，dev-board#409）。
// 只有单机模式的后端接受它；浏览器端拿不到路径，走 createFile + /upload 那条老路。
export function importLocalFile(projectId, sourcePath, parentId) {
  return request({
    url: `/api/projects/${projectId}/files/import-local`,
    method: 'POST',
    data: {
      sourcePath,
      parentId,
    },
    header: {
      'Content-Type': 'application/json',
    },
  });
}

// 重命名文件或文件夹
export function renameFile(projectId, fileId, name) {
  return request({
    url: `/api/projects/${projectId}/files/${fileId}/rename`,
    method: 'PUT',
    data: {
      name,
    },
    header: {
      'Content-Type': 'application/json',
    },
  });
}

// 删除文件或文件夹
export function deleteFile(projectId, fileId) {
  return request({
    url: `/api/projects/${projectId}/files/${fileId}`,
    method: 'DELETE',
  });
}



// 永久删除文件
export function deleteFilePerm(projectId, fileId) {
  return request({
    url: `/api/projects/${projectId}/files/${fileId}/permanent`,
    method: 'DELETE',
  });
}

// 还原文件
export function restoreFile(projectId, fileId) {
  return request({
    url: `/api/projects/${projectId}/files/${fileId}/restore`,
    method: 'POST',
  });
}

// 获取回收站文件
export function getRecycleBinFiles(projectId) {
  return request({
    url: `/api/projects/${projectId}/files/recycle-bin`,
    method: 'GET',
  });
}

// 移动文件或文件夹（拖拽排序）
export function moveFile(projectId, fileId, parentId, sortOrder) {
  return request({
    url: `/api/projects/${projectId}/files/${fileId}/move`,
    method: 'PUT',
    data: {
      parentId,
      sortOrder,
    },
    header: {
      'Content-Type': 'application/json',
    },
  });
}

// 批量删除文件/文件夹
export function batchDeleteFiles(projectId, fileIds) {
  return request({
    url: `/api/projects/${projectId}/files/batch/delete`,
    method: 'POST',
    data: {
      fileIds
    },
    header: {
      'Content-Type': 'application/json',
    },
  });
}

// 批量移动文件/文件夹
export function batchMoveFiles(projectId, fileIds, targetParentId) {
  return request({
    url: `/api/projects/${projectId}/files/batch/move`,
    method: 'POST',
    data: {
      fileIds,
      targetParentId
    },
    header: {
      'Content-Type': 'application/json',
    },
  });
}

// 批量复制文件/文件夹
export function batchCopyFiles(projectId, fileIds, targetParentId) {
  return request({
    url: `/api/projects/${projectId}/files/batch/copy`,
    method: 'POST',
    data: {
      fileIds,
      targetParentId
    },
    header: {
      'Content-Type': 'application/json',
    },
  });
}


// ===================== 脱敏处理 API =====================
// ===================== 脱敏处理 API =====================
export function getSensitiveOptions() {
  return request({
    url: '/api/sensitive/options',
    method: 'GET',
  });
}

export function desensitizeFile(payload) {
  return request({
    url: '/api/sensitive/desensitize',
    method: 'POST',
    data: payload,
    header: {
      'Content-Type': 'application/json',
    },
    timeout: 300000, // 5 minutes timeout for large files
  });
}

// 获取文件详情
export function getFileDetail(projectId, fileId) {
  return request({
    url: `/api/projects/${projectId}/files/${fileId}`,
    method: 'GET',
  });
}

// 获取文件下载URL
export function getFileDownloadUrl(fileId) {
  const baseUrl = getApiBaseUrl()
  return `${baseUrl}/api/files/${fileId}/download`
}

// 获取文件上传URL（multipart "file"，与下载同一 fileId 契约）
export function getFileUploadUrl(fileId) {
  const baseUrl = getApiBaseUrl()
  return `${baseUrl}/api/files/${fileId}/upload`
}

// Web 插件面板入口 URL（插件规范 v2.3）。
// - 绝对 http(s) URL：旧形态，原样返回，宿主 iframe 直接打开外部页面（行为不变）
// - web/ 之下的相对路径：映射到后端静态服务 <apiBase>/api/plugin-web/<id>/<entry>
// apiBase 走 getApiBaseUrl()（桌面壳注入优先），生产同源时它是空串，返回相对路径同样可用。
export function resolvePluginEntryUrl(pluginId, entry) {
  if (!entry) return ''
  const raw = String(entry)
  if (/^https?:\/\//i.test(raw)) return raw
  const baseUrl = getApiBaseUrl() || ''
  // /api/plugin-web/<id>/ 的 URL 空间直接映射 plugins/<id>/web/ 的内容
  // （PluginWebController 的 subPath 是相对 web/ 的），而 manifest.frontendEntry
  // 带着 web/ 前缀——不剥掉会拼出 web/web/ 双前缀，服务端 404
  const rel = raw.replace(/^\/+/, '').replace(/^web\//, '')
    .split('/').filter(Boolean).map(encodeURIComponent).join('/')
  if (!rel) return ''
  return `${baseUrl.replace(/\/$/, '')}/api/plugin-web/${encodeURIComponent(pluginId)}/${rel}`
}

// 获取文件文本内容
export function getFileText(fileId) {
  return request({
    url: `/api/files/${fileId}/text`,
    method: 'GET',
  });
}

// OCR：截图识别（后端调用阿里云）
export function ocrRecognize(imageBase64) {
  return request({
    url: '/api/ocr/recognize',
    method: 'POST',
    data: { imageBase64 },
    header: {
      'Content-Type': 'application/json',
    },
  });
}

// 收藏：我的收藏
export function getMyFavorites() {
  return request({
    url: '/api/favorites/my',
    method: 'GET',
  })
}

// 收藏：项目内收藏（支持搜索/限量，避免返回超大 meta/html 导致卡顿）
export function getProjectFavorites(projectId, q = '', limit = 80) {
  const qs = []
  if (q) qs.push(`q=${encodeURIComponent(q)}`)
  if (limit != null) qs.push(`limit=${encodeURIComponent(String(limit))}`)
  const queryString = qs.length ? `?${qs.join('&')}` : ''
  return request({
    url: `/api/projects/${projectId}/favorites${queryString}`,
    method: 'GET',
  })
}

// ===================== EasyVoice (TTS) =====================

export function getTtsVoices() {
  return request({
    url: '/api/tts/voices',
    method: 'GET'
  });
}

export function generateTtsAudio(payload) {
  return request({
    url: '/api/tts/generate',
    method: 'POST',
    data: payload,
    responseType: 'arraybuffer'
  });
}

export function createProjectFavorite(projectId, payload) {
  return request({
    url: `/api/projects/${projectId}/favorites`,
    method: 'POST',
    data: payload,
    header: {
      'Content-Type': 'application/json',
    },
  })
}

// 证据链接 EvidenceLink（报告文字 <-> 底稿文件关联事实表，spec §2.2）。
// 旧 /doc-links POST 已 410，一律走这组。linkKey 只含 [A-Za-z0-9_]。
export function createEvidenceLink(projectId, body) {
  return request({ url: `/api/projects/${projectId}/evidence-links`, method: 'POST', data: body })
}
export function getEvidenceLink(projectId, linkKey) {
  return request({ url: `/api/projects/${projectId}/evidence-links/${encodeURIComponent(linkKey)}`, method: 'GET' })
}
// query 里 null/undefined 的键剔掉（PluginPane 会传 {status: undefined}），免得序列化成 "undefined"
export function listEvidenceLinks(projectId, query = {}) {
  const data = {}
  for (const k of Object.keys(query || {})) if (query[k] != null && query[k] !== '') data[k] = query[k]
  return request({ url: `/api/projects/${projectId}/evidence-links`, method: 'GET', data })
}
export function addEvidenceTargets(projectId, linkKey, targets) {
  return request({ url: `/api/projects/${projectId}/evidence-links/${encodeURIComponent(linkKey)}/targets`, method: 'POST', data: targets })
}
export function updateEvidenceTarget(projectId, targetId, patch) {
  return request({ url: `/api/projects/${projectId}/evidence-links/targets/${targetId}`, method: 'PATCH', data: patch })
}
export function removeEvidenceTarget(projectId, targetId) {
  return request({ url: `/api/projects/${projectId}/evidence-links/targets/${targetId}`, method: 'DELETE' })
}
export function deleteEvidenceLink(projectId, linkKey) {
  return request({ url: `/api/projects/${projectId}/evidence-links/${encodeURIComponent(linkKey)}`, method: 'DELETE' })
}
export function reportEvidenceAnchors(projectId, docFileId, reports) {
  return request({ url: `/api/projects/${projectId}/evidence-links/anchors/report`, method: 'POST', data: { docFileId, reports } })
}
export function keepEvidenceAnchor(projectId, linkKey, text) {
  return request({ url: `/api/projects/${projectId}/evidence-links/${encodeURIComponent(linkKey)}/keep`, method: 'POST', data: { text } })
}
export function rebindEvidenceLink(projectId, linkKey, body) {
  return request({ url: `/api/projects/${projectId}/evidence-links/${encodeURIComponent(linkKey)}/rebind`, method: 'POST', data: body })
}
export function evidenceRefCounts(projectId, fileIds) {
  return request({ url: `/api/projects/${projectId}/evidence-links/ref-counts`, method: 'GET', data: { fileIds: (fileIds || []).join(',') } })
}

// 文档解析 /「依据」窗格（dev-board#181/#182）。契约见 .claude/agents/doc-insight.md。
// 四个端点全在 /api/projects/{pid}/insight 之下；未登录一律 200 + {code:4010}，
// 由 request() 的统一信封处理（这里不另做分支）。
/** 发起解析（异步）。返回时 run 已落库为 RUNNING，调用方立刻可以轮询 getInsight。 */
export function parseDocInsight(projectId, docFileId) {
  return request({ url: `/api/projects/${projectId}/insight/parse`, method: 'POST', data: { docFileId } })
}
/** 最近一次解析结果：run + 实体（列表里不带检索详情）+ 全部发现（含 detail）。run=null = 没解析过。 */
export function getDocInsight(projectId, docFileId) {
  return request({ url: `/api/projects/${projectId}/insight`, method: 'GET', data: { docFileId } })
}
/** 单个实体的检索详情（列表刻意瘦身，展开时才拉）。 */
export function getDocInsightEntity(projectId, entityId) {
  return request({ url: `/api/projects/${projectId}/insight/entities/${entityId}`, method: 'GET' })
}
/** 重新检索一个实体（绕过 7 天缓存，花外部库额度，要写权限）。 */
export function refreshDocInsightEntity(projectId, entityId) {
  return request({ url: `/api/projects/${projectId}/insight/entities/${entityId}/refresh`, method: 'POST' })
}

export function deleteFavorite(favoriteId) {
  return request({
    url: `/api/favorites/${favoriteId}`,
    method: 'DELETE',
  })
}

export function getFavoriteImageUrl(favoriteId) {
  const baseUrl = getApiBaseUrl()
  const token = getSessionId()
  return `${baseUrl}/api/favorites/${favoriteId}/image?token=${token || ''}`
}

// 剪贴板（Paste-like）
export function listClipboard(q, limit = 50) {
  const queryString = q ? `?q=${encodeURIComponent(q)}&limit=${limit}` : `?limit=${limit}`
  return request({
    url: `/api/clipboard${queryString}`,
    method: 'GET',
  })
}

// 文件缓存区用量：{ fileCount, totalBytes, limited, maxFiles, maxBytes }
// 上限常量只在后端定义一处，前端不复制，避免改额度时两边不一致。
export function getStageUsage(projectId, folderId) {
  const qs = folderId ? `?folderId=${encodeURIComponent(folderId)}` : ''
  return request({
    url: `/api/projects/${projectId}/files/stage/usage${qs}`,
    method: 'GET',
  }).then(unwrapEnvelope)
}

// 本机文件存储位置：{ path, defaultPath, custom, available, movedAt, entitled }
// 只读展示不要求权益——权益失效后用户仍须看得到自己的数据在哪（entitled 告诉前端能不能改）
export function getStorageLocation() {
  return request({
    url: '/api/storage/location',
    method: 'GET',
  }).then(unwrapEnvelope)
}

// 迁移存储位置。后端先复制再校验再切指针，原目录保留为备份；失败会回滚且保持原位置。
// 返回整个响应体（含 message），调用方要区分 code=0 与失败文案。
export function moveStorageLocation(path) {
  return request({
    url: '/api/storage/location',
    method: 'POST',
    data: { path },
    header: { 'Content-Type': 'application/json' },
  })
}

// 恢复默认存储位置。只换指针，不搬也不删任何文件——自选目录里的数据原样留在原处。
// 不要求权益：权益失效 + 自选目录不可访问时，这是用户唯一的出口。
export function resetStorageLocation() {
  return request({
    url: '/api/storage/location/reset',
    method: 'POST',
    header: { 'Content-Type': 'application/json' },
  })
}

export function saveClipboardText(text) {
  return request({
    url: '/api/clipboard/text',
    method: 'POST',
    data: { text },
    header: { 'Content-Type': 'application/json' },
  })
}

export function saveClipboardFile(fileObj, type = 'FILE') {
  const baseUrl = getApiBaseUrl()
  const url = `${baseUrl}/api/clipboard/file`
  const sessionId = getSessionId()

  return new Promise((resolve, reject) => {
    // Use native XMLHttpRequest or fetch to ensure Blob/File upload works reliably in H5/Electron
    const formData = new FormData()
    // fileObj.file is the DOM File from project-overview.vue
    if (fileObj.file) {
      formData.append('file', fileObj.file)
    } else {
      reject(new Error('No file object'))
      return
    }
    if (type) formData.append('type', type)

    const xhr = new XMLHttpRequest()
    xhr.open('POST', url)
    if (sessionId) {
      xhr.setRequestHeader('X-Session-Id', sessionId)
    }

    xhr.onload = () => {
      if (xhr.status === 200) {
        try {
          const data = JSON.parse(xhr.responseText)
          if (data.code === 0) {
            resolve(data)
          } else {
            reject(new Error(data.message || t('common.uploadFailed')))
          }
        } catch (e) {
          reject(new Error(t('common.parseResponseFailed')))
        }
      } else {
        reject(new Error('HTTP Error ' + xhr.status))
      }
    }

    xhr.onerror = () => reject(new Error('Network Error'))

    xhr.send(formData)
  })
}

export function deleteClipboardItem(id) {
  return request({
    url: `/api/clipboard/${id}`,
    method: 'DELETE',
  })
}

/**
 * 提交一条用户反馈（正文 + 图片 + 语音，一次 multipart 请求）。
 * 分步上传会在网络抖动时留下一堆没有正文的空反馈——而反馈恰恰是「出问题时」提交的。
 * @param {{kind:string,text:string,projectId:number|null,page:string,clientContext:object}} payload
 * @param {File[]} files 图片（image/*）与语音（audio/*），服务端按 MIME 分类
 */
export function submitFeedback(payload, files = []) {
  const baseUrl = getApiBaseUrl()
  const sessionId = getSessionId()
  const form = new FormData()
  form.append('payload', JSON.stringify(payload || {}))
  for (const f of files) {
    if (f) form.append('files', f, f.name)
  }
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest()
    xhr.open('POST', `${baseUrl.replace(/\/$/, '')}/api/feedback`)
    if (sessionId) xhr.setRequestHeader('X-Session-Id', sessionId)
    xhr.onload = () => {
      if (xhr.status !== 200) {
        reject(new Error(t('common.submitFailedWithStatus', { status: xhr.status })))
        return
      }
      try {
        const data = JSON.parse(xhr.responseText)
        if (data.code === 0) resolve(data)
        else reject(new Error(data.message || t('common.submitFailed')))
      } catch (e) {
        reject(new Error(t('common.parseResponseFailed')))
      }
    }
    xhr.onerror = () => reject(new Error(t('common.networkError')))
    xhr.send(form)
  })
}

/** 我的反馈（浮窗「我的反馈」视图用）：只回当前用户自己提交过的行，不需要管理员。 */
export function getMyFeedback() {
  return request({ url: '/api/feedback/mine', method: 'GET' })
}

/** 反馈列表（管理员）。 */
export function getFeedbackList(status = '', limit = 50) {
  const q = status ? `?status=${encodeURIComponent(status)}&limit=${limit}` : `?limit=${limit}`
  return request({ url: `/api/feedback${q}`, method: 'GET' })
}

/** 单条反馈详情：附件清单 + 分诊结论 + 提交现场（管理员）。 */
export function getFeedbackDetail(id) {
  return request({ url: `/api/feedback/${id}`, method: 'GET' })
}

/** 优化者：手动跑一轮 / 查状态（管理员）。 */
export function runOptimizer() {
  return request({ url: '/api/optimizer/run', method: 'POST' })
}

export function getOptimizerStatus() {
  return request({ url: '/api/optimizer/status', method: 'GET' })
}

export const getProjectVariables = (projectId) => {
  return request({
    url: `/api/variables/project/${projectId}`,
    method: 'GET'
  })
}

export const saveProjectVariable = (data) => {
  return request({
    url: '/api/variables',
    method: 'POST',
    data
  })
}

export const deleteProjectVariable = (id) => {
  return request({
    url: `/api/variables/${id}`,
    method: 'DELETE'
  })
}

// 用户变量（用户收藏/自维护）
export const getUserVariables = () => {
  return request({
    url: `/api/variables/user`,
    method: 'GET'
  })
}

export const saveUserVariable = (data) => {
  return request({
    url: '/api/variables/user',
    method: 'POST',
    data
  })
}

export const deleteUserVariable = (id) => {
  return request({
    url: `/api/variables/user/${id}`,
    method: 'DELETE'
  })
}

// ===================== 项目成员管理 =====================
export function getProjectMembers(projectId) {
  return request({
    url: `/api/projects/${projectId}/members`,
    method: 'GET'
  })
}

export function addProjectMember(projectId, username, role) {
  return request({
    url: `/api/projects/${projectId}/members`,
    method: 'POST',
    data: { username, role },
    header: { 'Content-Type': 'application/json' }
  })
}

export function removeProjectMember(projectId, userId) {
  return request({
    url: `/api/projects/${projectId}/members/${userId}`,
    method: 'DELETE'
  })
}

// ===================== 文件变量管理 =====================
export function getFileVariables(fileId) {
  return request({
    url: `/api/file-variables?fileId=${fileId}`,
    method: 'GET'
  })
}

export function saveFileVariable(data) {
  return request({
    url: '/api/file-variables',
    method: 'POST',
    data,
    header: { 'Content-Type': 'application/json' }
  })
}

export function deleteFileVariable(id) {
  return request({
    url: `/api/file-variables/${id}`,
    method: 'DELETE'
  })
}

// ===================== 用户活动日志 =====================
export function logActivity(actionType, targetId, targetName, duration, metaInfo, projectId) {
  return request({
    url: '/api/activity/log',
    method: 'POST',
    data: {
      actionType,
      targetId,
      targetName,
      duration,
      metaInfo,
      projectId
    },
    header: { 'Content-Type': 'application/json' }
  })
}

export function getUserActivityHistory() {
  return request({
    url: '/api/activity/history',
    method: 'GET'
  })
}

// ===================== 产品埋点（匿名使用统计，与活动日志无关） =====================
export function logTelemetryEvent(eventName, attrs) {
  return request({
    url: '/api/telemetry/event',
    method: 'POST',
    data: { eventName, attrs },
    header: { 'Content-Type': 'application/json' }
  })
}

// 应用语言（zh-CN / en-US）。权威源在前端 utils/appLanguage.js，这两个函数只负责
// 把值镜像到后端 system_setting（后端据此选 system prompt 语言与文案语言）。
export function getAppLanguageRemote() {
  return request({
    url: '/api/app/language',
    method: 'GET'
  })
}

export function saveAppLanguageRemote(language) {
  return request({
    url: '/api/app/language',
    method: 'POST',
    data: { language },
    header: { 'Content-Type': 'application/json' }
  })
}

export function getTelemetrySettings() {
  return request({
    url: '/api/telemetry/settings',
    method: 'GET'
  })
}

export function updateTelemetrySettings(payload) {
  return request({
    url: '/api/telemetry/settings',
    method: 'POST',
    data: payload,
    header: { 'Content-Type': 'application/json' }
  })
}

export function getTelemetrySummary(days = 30) {
  return request({
    url: `/api/telemetry/summary?days=${days}`,
    method: 'GET'
  })
}

// ===================== 尽调清单管理 (Due Diligence) =====================
export function getDdRequests(projectId) {
  return request({
    url: `/api/dd/projects/${projectId}`,
    method: 'GET'
  })
}

export function createDdRequest(projectId, payload) {
  return request({
    url: `/api/dd/projects/${projectId}`,
    method: 'POST',
    data: payload,
    header: { 'Content-Type': 'application/json' }
  })
}

export function getDdRequestDetails(requestId) {
  return request({
    url: `/api/dd/requests/${requestId}`,
    method: 'GET'
  })
}

export function updateDdItemStatus(itemId, status) {
  return request({
    url: `/api/dd/items/${itemId}/status`,
    method: 'PUT',
    data: { status },
    header: { 'Content-Type': 'application/json' }
  })
}

export function updateDdItemInfo(itemId, title, description) {
  return request({
    url: `/api/dd/items/${itemId}/info`,
    method: 'PUT',
    data: { title, description },
    header: { 'Content-Type': 'application/json' }
  })
}

export function addDdItemComment(itemId, content) {
  return request({
    url: `/api/dd/items/${itemId}/comments`,
    method: 'POST',
    data: { content },
    header: { 'Content-Type': 'application/json' }
  })
}

export function getDdItemComments(itemId) {
  return request({
    url: `/api/dd/items/${itemId}/comments`,
    method: 'GET'
  })
}

export function deleteDdItem(itemId) {
  return request({
    url: `/api/dd/items/${itemId}`,
    method: 'DELETE'
  })
}

export function deleteDdRequest(requestId) {
  return request({
    url: `/api/dd/requests/${requestId}`,
    method: 'DELETE'
  })
}

export function copyDdRequest(requestId) {
  return request({
    url: `/api/dd/requests/${requestId}/copy`,
    method: 'POST'
  })
}

// ===================== 股东大会核查 (Shareholder Meeting) =====================
export function getShareholderMeetingChecks(projectId) {
  return request({
    url: `/api/shareholder-meeting/projects/${projectId}`,
    method: 'GET'
  })
}

export function createShareholderMeetingCheck(projectId, payload) {
  return request({
    url: `/api/shareholder-meeting/projects/${projectId}`,
    method: 'POST',
    data: payload,
    header: { 'Content-Type': 'application/json' }
  })
}

export function updateShareholderMeetingCheck(checkId, payload) {
  return request({
    url: `/api/shareholder-meeting/${checkId}`,
    method: 'PUT',
    data: payload,
    header: { 'Content-Type': 'application/json' }
  })
}

export function deleteShareholderMeetingCheck(checkId) {
  return request({
    url: `/api/shareholder-meeting/${checkId}`,
    method: 'DELETE'
  })
}

export function attachShareholderMeetingMaterial(checkId, slot, fileId) {
  return request({
    url: `/api/shareholder-meeting/${checkId}/materials`,
    method: 'POST',
    data: { slot, fileId },
    header: { 'Content-Type': 'application/json' }
  })
}

export function detachShareholderMeetingMaterial(checkId, slot, fileId) {
  return request({
    url: `/api/shareholder-meeting/${checkId}/materials?slot=${encodeURIComponent(slot)}&fileId=${fileId}`,
    method: 'DELETE'
  })
}

export function fetchShareholderMeetingCninfo(checkId, market) {
  const suffix = market ? `?market=${encodeURIComponent(market)}` : ''
  return request({
    url: `/api/shareholder-meeting/${checkId}/fetch-cninfo${suffix}`,
    method: 'POST'
  })
}

export function startShareholderMeetingCheck(checkId) {
  return request({
    url: `/api/shareholder-meeting/${checkId}/start`,
    method: 'POST'
  })
}

export function bindShareholderMeetingConversation(checkId, conversationId, status) {
  return request({
    url: `/api/shareholder-meeting/${checkId}/conversation`,
    method: 'PUT',
    data: { conversationId, status },
    header: { 'Content-Type': 'application/json' }
  })
}

// ===================== 会议录音 (Meeting Recording) =====================
export function createMeetingRecording(projectId) {
  return request({
    url: `/api/meetings/projects/${projectId}`,
    method: 'POST'
  })
}

export function getMeetingRecordings(projectId) {
  return request({
    url: `/api/meetings/projects/${projectId}`,
    method: 'GET'
  })
}

// 转写中后端会顺手向听悟问一次进度（poll-on-read），前端定时调它即可推进状态
export function getMeetingRecording(meetingId) {
  return request({
    url: `/api/meetings/${meetingId}`,
    method: 'GET'
  })
}

export function finishMeetingRecording(meetingId, durationMs, transcribe) {
  return request({
    url: `/api/meetings/${meetingId}/finish`,
    method: 'POST',
    data: { durationMs, transcribe },
    header: { 'Content-Type': 'application/json' }
  })
}

export function transcribeMeetingRecording(meetingId) {
  return request({
    url: `/api/meetings/${meetingId}/transcribe`,
    method: 'POST'
  })
}

// 资源管理器右键转写：把项目里已有的音频文件注册成会议记录并（凭证已配时）自动提交转写。
// 返回 { meeting, configured, submitted }（dev-board#227）
export function registerMeetingFromFile(projectId, fileId) {
  return request({
    url: `/api/meetings/projects/${projectId}/register-file`,
    method: 'POST',
    data: { fileId },
    header: { 'Content-Type': 'application/json' }
  })
}

export function updateMeetingRecording(meetingId, payload) {
  return request({
    url: `/api/meetings/${meetingId}`,
    method: 'PATCH',
    data: payload,
    header: { 'Content-Type': 'application/json' }
  })
}

export function exportMeetingTranscript(meetingId) {
  return request({
    url: `/api/meetings/${meetingId}/export`,
    method: 'POST'
  })
}

export function getMeetingMinutesPrompt(meetingId) {
  return request({
    url: `/api/meetings/${meetingId}/minutes-prompt`,
    method: 'POST'
  })
}

export function deleteMeetingRecording(meetingId) {
  return request({
    url: `/api/meetings/${meetingId}`,
    method: 'DELETE'
  })
}

// ==================== 编辑器操作结果回调 ====================

/**
 * 发送编辑器操作结果到后端（双轨迁移：新路由 /editor-result；后端同版本起
 * 保留旧路由 /wps-result 别名供旧前端使用，见 docs/AI_ARCHITECTURE.md Phase 3）
 * @param {string} conversationId - 会话 ID
 * @param {string} requestId - 请求 ID
 * @param {boolean} success - 是否成功
 * @param {Object} data - 结果数据
 * @param {string} error - 错误信息
 */
export function sendEditorResult(conversationId, requestId, success, data, error = null) {
  return request({
    url: '/api/ai/agent/editor-result',
    method: 'POST',
    data: {
      conversationId,
      requestId,
      success,
      data,
      error
    },
    header: { 'Content-Type': 'application/json' }
  })
}

// ==================== 标签管理 (Tag Management) ====================
export function getProjectTags(projectId) {
  return request({
    url: `/api/projects/${projectId}/tags`,
    method: 'GET'
  })
}

export function createTag(projectId, data) {
  return request({
    url: `/api/projects/${projectId}/tags`,
    method: 'POST',
    data,
    header: { 'Content-Type': 'application/json' }
  })
}

export function updateTag(projectId, tagId, data) {
  return request({
    url: `/api/projects/${projectId}/tags/${tagId}`,
    method: 'PUT',
    data,
    header: { 'Content-Type': 'application/json' }
  })
}

export function deleteTag(projectId, tagId) {
  return request({
    url: `/api/projects/${projectId}/tags/${tagId}`,
    method: 'DELETE'
  })
}

export function addTagToFile(projectId, fileId, tagId) {
  return request({
    url: `/api/projects/${projectId}/files/${fileId}/tags`,
    method: 'POST',
    data: { tagId },
    header: { 'Content-Type': 'application/json' }
  })
}

export function removeTagFromFile(projectId, fileId, tagId) {
  return request({
    url: `/api/projects/${projectId}/files/${fileId}/tags/${tagId}`,
    method: 'DELETE'
  })
}

// 全文搜索
export function searchProjectContent(projectId, payload) {
  return request({
    url: `/api/projects/${projectId}/search`,
    method: 'POST',
    data: payload,
    header: { 'Content-Type': 'application/json' }
  })
}

export default {
  getApiBaseUrl,
  request,
  exportAiDocx,
  fetchCompanyBasicInfo,
  createProject,
  openLocalProject,
  getProjectLocalPath,
  getFileLocalPath,
  register,
  login,
  clientLogin,
  inviteClient,
  getCurrentUser,
  logout,
  getMyProjects,
  deleteProject,
  getProject,
  getProjectFiles,
  createFolder,
  createFile,
  importLocalFile,
  renameFile,
  deleteFile,
  moveFile,
  getFileDetail,
  getFileDownloadUrl,
  getFileUploadUrl,
  ocrRecognize,
  getMyFavorites,
  getProjectFavorites,
  createProjectFavorite,
  deleteFavorite,
  getFavoriteImageUrl,
  listClipboard,
  saveClipboardText,
  deleteClipboardItem,
  getProjectVariables,
  saveProjectVariable,
  deleteProjectVariable,
  getUserVariables,
  saveUserVariable,
  deleteUserVariable,
  getProjectMembers,
  addProjectMember,
  removeProjectMember,
  getFileVariables,
  saveFileVariable,
  deleteFileVariable,
  logActivity,
  getUserActivityHistory,
  getAdminConfig,
  saveAdminConfig,
  getAdminUsers,
  getPlatformServices,
  getPlatformServiceRemote,
  setPlatformServiceProvider,
  savePlatformBudget,
  getMeetingAsrNotice,
  acknowledgeMeetingAsrNotice,
  // DD Files
  getDdRequests,
  createDdRequest,
  getDdRequestDetails,
  updateDdItemStatus,
  updateDdItemInfo,
  addDdItemComment,
  getDdItemComments,
  deleteDdItem,
  deleteDdRequest,
  copyDdRequest,
  getShareholderMeetingChecks,
  createShareholderMeetingCheck,
  updateShareholderMeetingCheck,
  deleteShareholderMeetingCheck,
  attachShareholderMeetingMaterial,
  detachShareholderMeetingMaterial,
  fetchShareholderMeetingCninfo,
  startShareholderMeetingCheck,
  bindShareholderMeetingConversation,
  // Desensitization
  getSensitiveOptions,
  desensitizeFile,
  // WPS 操作
  sendEditorResult,
  addDdRequestItems(requestId, content) {
    return request({
      url: `/api/dd/requests/${requestId}/items`,
      method: 'POST',
      data: { content },
      header: { 'Content-Type': 'application/json' }
    })
  },
  addDdItem(requestId, parentId) {
    return request({
      url: `/api/dd/requests/${requestId}/item`,
      method: 'POST',
      data: { parentId },
      header: { 'Content-Type': 'application/json' }
    })
  },
  moveDdItem(itemId, parentId) {
    return request({
      url: `/api/dd/items/${itemId}/parent`,
      method: 'PUT',
      data: { parentId },
      header: { 'Content-Type': 'application/json' }
    })
  },
  updateDdRequest(requestId, name) {
    return request({
      url: `/api/dd/requests/${requestId}`,
      method: 'PUT',
      data: { name },
      header: { 'Content-Type': 'application/json' }
    })
  },

  /**
   * 文档比较 - 提取两个文档的文本内容
   * @param {number} sourceId 源文档 ID（基准文档）
   * @param {number} targetId 目标文档 ID（比较对象）
   * @returns {Promise<{code: number, data: {source: {id, name, text}, target: {id, name, text}}}>}
   */
  compareDocuments(sourceId, targetId) {
    return request({
      url: `/api/files/compare?sourceId=${sourceId}&targetId=${targetId}`,
      method: 'GET'
    })
  },
  // Tag Management
  getProjectTags,
  createTag,
  updateTag,
  deleteTag,
  addTagToFile,
  removeTagFromFile,
  searchProjectContent,
  // 项目概览页（pages/project-home）
  getProjectOverviewStats,
  getProjectProfile,
  saveProjectProfileField,
  getProjectConversations,
  getProjectTasks
}

// ==================== 项目概览页（pages/project-home） ====================
// 五个端点一律返回信封：request() 见到 {code:0,...} 时 resolve 的是整个响应体，
// 所以调用方一律写 `const res = await getProjectProfile(id); res.data.fields`。
// 反例：getMyProjects 走的是裸数组（ProjectController 直接返 List），别照抄这里加 .data。

export function getProjectOverviewStats(projectId) {
  return request({
    url: `/api/projects/${projectId}/overview/stats`,
    method: 'GET'
  });
}

export function getProjectProfile(projectId) {
  return request({
    url: `/api/projects/${projectId}/profile`,
    method: 'GET'
  });
}

/** value 传空串 = 清空该字段（服务端删行；openedAt 因此回落建档时间默认值）。 */
export function saveProjectProfileField(projectId, fieldKey, value) {
  return request({
    url: `/api/projects/${projectId}/profile/${encodeURIComponent(fieldKey)}`,
    method: 'PUT',
    data: { value },
    header: { 'Content-Type': 'application/json' }
  });
}

/** options.before / options.beforeId 是上一页最后一条的 (updatedAt, conversationId) 复合游标，成对传。 */
export function getProjectConversations(projectId, options = {}) {
  return request({
    url: `/api/projects/${projectId}/conversations`,
    method: 'GET',
    params: {
      limit: options.limit || 20,
      ...(options.before ? { before: options.before } : {}),
      ...(options.beforeId ? { beforeId: options.beforeId } : {})
    }
  });
}

/** B 期（日历/任务系统）起返回真实任务列表，响应形状 {code:0,data:{tasks:[...]}} 不变。 */
export function getProjectTasks(projectId) {
  return request({
    url: `/api/projects/${projectId}/tasks`,
    method: 'GET'
  });
}

// ==================== 日历/任务（B 期，spec: docs/superpowers/specs/2026-08-20-calendar-view-design.md） ====================

/** data: {projectId, fileId?, title, dueDate(ISO 日期), dueTime?(HH:mm)}，source 由后端定为 user。 */
export function createTask(data) {
  return request({
    url: '/api/tasks',
    method: 'POST',
    data
  });
}

/** data 可含 title/dueDate/dueTime/status(OPEN|DONE) 的任意子集；dueTime 传 null 表示清空。 */
export function updateTask(taskId, data) {
  return request({
    url: `/api/tasks/${taskId}`,
    method: 'PUT',
    data
  });
}

export function deleteTask(taskId) {
  return request({
    url: `/api/tasks/${taskId}`,
    method: 'DELETE'
  });
}

/** 跨项目全盘日历。from/to 为 ISO 日期（含端点），返回 {code:0,data:{tasks:[...含 projectName]}}。 */
export function getCalendarTasks(from, to) {
  return request({
    url: '/api/calendar',
    method: 'GET',
    params: { from, to }
  });
}

// ==================== 版本记录 ====================
// 术语对齐 spec 第四节：对外只说「版本 / 本次工作」，不说 commit / branch。

export function getVersionStatus(projectId) {
  return request({
    url: `/api/projects/${projectId}/version/status`,
    method: 'GET'
  });
}

export function enableVersionControl(projectId) {
  return request({
    url: `/api/projects/${projectId}/version/enable`,
    method: 'POST'
  });
}

// 关闭版本记录并删除全部历史（dev-board#438）。只有项目负责人能调；工作区里的文件一个不动。
export function disableVersionControl(projectId) {
  return request({
    url: `/api/projects/${projectId}/version/disable`,
    method: 'POST'
  });
}

export function getVersionTimeline(projectId, limit = 50, fileId) {
  let url = `/api/projects/${projectId}/version/timeline?limit=${limit}`
  if (fileId) url += `&fileId=${fileId}`
  return request({
    url,
    method: 'GET'
  });
}

export function getVersionChanges(projectId, sha) {
  return request({
    url: `/api/projects/${projectId}/version/versions/${encodeURIComponent(sha)}/changes`,
    method: 'GET'
  });
}

export function endWorkSession(projectId, title) {
  return request({
    url: `/api/projects/${projectId}/version/session/end`,
    method: 'POST',
    data: { title: title || '' }
  });
}

export function discardWorkSession(projectId) {
  return request({
    url: `/api/projects/${projectId}/version/session/discard`,
    method: 'POST'
  });
}

export function resumeWorkSession(projectId) {
  return request({
    url: `/api/projects/${projectId}/version/session/resume`,
    method: 'POST'
  });
}

export function revertToVersion(projectId, ref) {
  return request({
    url: `/api/projects/${projectId}/version/revert`,
    method: 'POST',
    data: { ref }
  });
}

// 标记重要版本（需命名，如「发客户第一稿」）。
export function markVersionMilestone(projectId, sha, name) {
  return request({
    url: `/api/projects/${projectId}/version/versions/${encodeURIComponent(sha)}/milestone`,
    method: 'POST',
    data: { name }
  });
}

// 版本对比降级：取某一版某文件抽取出的纯文本。
export function getVersionFileText(projectId, ref, path) {
  return request({
    url: `/api/projects/${projectId}/version/versions/${encodeURIComponent(ref)}/file-text?path=${encodeURIComponent(path)}`,
    method: 'GET'
  });
}

// 版本对比：取某一版某文件的原始字节（octet-stream）。返回 Uint8Array。
// 走裸 fetch 而非上面的 request()——后端这个接口成功时是二进制流，出错时才是
// {code,message} JSON（HTTP 恒 200，见 VersionController.onVersionError），
// request() 的 uni.request 封装是围绕统一 {code,data} JSON 设计的，二进制走裸
// fetch 更直接（MarkdownPreview.vue 已有同样的 fetch+getAuthHeaders 先例）。
export async function fetchVersionFileBytes(projectId, ref, path) {
  const baseUrl = getApiBaseUrl();
  const url = `${baseUrl.replace(/\/$/, '')}/api/projects/${projectId}/version/versions/${encodeURIComponent(ref)}/file-bytes?path=${encodeURIComponent(path)}`;
  const resp = await fetch(url, { headers: getAuthHeaders() });
  const ct = resp.headers.get('content-type') || '';
  if (ct.includes('application/json')) {
    const j = await resp.json();
    throw new Error((j && j.message) || t('common.readVersionFileFailed'));
  }
  if (!resp.ok) throw new Error(t('common.readVersionFileFailed'));
  return new Uint8Array(await resp.arrayBuffer());
}

// ---- 稿：创建、双向切线、采纳/裁决/中止/放弃（第 3 期） -----------------

// 另起一稿。ref 为空取当前版本。
export function createDraft(projectId, ref, name) {
  return request({
    url: `/api/projects/${projectId}/version/draft`,
    method: 'POST',
    data: { ref: ref || '', name }
  });
}

export function listDrafts(projectId) {
  return request({
    url: `/api/projects/${projectId}/version/drafts`,
    method: 'GET'
  });
}

export function switchToDraft(projectId, draftId) {
  return request({
    url: `/api/projects/${projectId}/version/draft/${draftId}/switch`,
    method: 'POST'
  });
}

export function switchToMainline(projectId) {
  return request({
    url: `/api/projects/${projectId}/version/switch-mainline`,
    method: 'POST'
  });
}

export function adoptDraft(projectId, draftId) {
  return request({
    url: `/api/projects/${projectId}/version/draft/${draftId}/adopt`,
    method: 'POST'
  });
}

// resolutions: { [path]: 'MAIN' | 'DRAFT' | 'BOTH' }
export function resolveAdopt(projectId, draftId, resolutions) {
  return request({
    url: `/api/projects/${projectId}/version/draft/${draftId}/resolve`,
    method: 'POST',
    data: { resolutions }
  });
}

export function abortAdopt(projectId, draftId) {
  return request({
    url: `/api/projects/${projectId}/version/draft/${draftId}/abort-adopt`,
    method: 'POST'
  });
}

export function abandonDraft(projectId, draftId) {
  return request({
    url: `/api/projects/${projectId}/version/draft/${draftId}/abandon`,
    method: 'POST'
  });
}

// 某一份进行中稿自己的时间线（VersionTimeline 的分叉线用，Phase B）。响应形状与
// /timeline 一致（含 parents），后端并行开发中——调用方需自行处理 404/失败降级，
// 不要假设这个端点一定存在。
export function getDraftTimeline(projectId, draftId, limit = 50) {
  return request({
    url: `/api/projects/${projectId}/version/drafts/${draftId}/timeline?limit=${limit}`,
    method: 'GET'
  });
}

// ==================== 云端协作（v2）====================
// 术语：push=上传到云端 pull=从云端更新 clone=从云端接一个项目。界面零 Git 术语。

export function cloudConnect(serverUrl, username, password, deviceName) {
  return request({ url: '/api/cloud/connect', method: 'POST',
    data: { serverUrl, username, password, deviceName } })
}

export function listCloudConnections() {
  return request({ url: '/api/cloud/connections', method: 'GET' })
}

export function disconnectCloudConnection(connectionId) {
  return request({ url: `/api/cloud/connections/${connectionId}/disconnect`, method: 'POST' })
}

export function listRemoteProjects(connectionId) {
  return request({ url: `/api/cloud/connections/${connectionId}/remote-projects`, method: 'GET' })
}

// connectionId 可省：不传就由后端连官方案件库再共享（零配置直连）。
// 显式传 undefined/null 时不要把这个键发上去——后端按「有没有这个键」分流。
export function shareProjectToCloud(projectId, connectionId) {
  const data = {}
  if (connectionId !== undefined && connectionId !== null) data.connectionId = connectionId
  return request({ url: `/api/cloud/projects/${projectId}/share`, method: 'POST', data })
}

// 官方团队案件库：{available, connected, serverUrl, username}。available=false 表示
// 本站暂不提供（国际站），界面就只留自建案件库那条路。
export function getOfficialCloud() {
  return request({ url: '/api/cloud/official', method: 'GET' })
}

// 用本机的官网账户一键连上官方案件库（幂等，重复调不会多建连接）。
export function connectOfficialCloud() {
  return request({ url: '/api/cloud/connect-official', method: 'POST' })
}

export function acceptCloudProject(connectionId, remoteProjectId) {
  return request({ url: '/api/cloud/accept', method: 'POST',
    data: { connectionId, remoteProjectId } })
}

export function getCloudStatus(projectId) {
  return request({ url: `/api/cloud/projects/${projectId}/status`, method: 'GET' })
}

export function checkCloud(projectId) {
  return request({ url: `/api/cloud/projects/${projectId}/check`, method: 'POST' })
}

export function uploadToCloud(projectId) {
  return request({ url: `/api/cloud/projects/${projectId}/upload`, method: 'POST' })
}

export function updateFromCloud(projectId) {
  return request({ url: `/api/cloud/projects/${projectId}/update`, method: 'POST' })
}

// resolutions: { [path]: 'MAIN' | 'DRAFT' | 'BOTH' }（语境映射见 AdoptConflictDialog）
export function resolveCloudMerge(projectId, resolutions) {
  return request({ url: `/api/cloud/projects/${projectId}/resolve`, method: 'POST',
    data: { resolutions } })
}

export function abortCloudMerge(projectId) {
  return request({ url: `/api/cloud/projects/${projectId}/abort`, method: 'POST' })
}

export function resolveSessionEnd(projectId, sessionId, resolutions) {
  return request({ url: `/api/projects/${projectId}/version/session/resolve-end`, method: 'POST',
    data: { sessionId, resolutions } })
}

export function abortSessionEnd(projectId) {
  return request({ url: `/api/projects/${projectId}/version/session/abort-end`, method: 'POST' })
}

// 成员代理：转发到云端项目实际的成员列表/邀请（不是本地项目成员）。
export function getCloudMembers(projectId) {
  return request({ url: `/api/cloud/projects/${projectId}/members`, method: 'GET' })
}

// 先查人：回 {found, displayName, avatarUrl, maskedContact, alreadyMember, currentRole, message}。
// found=false 也是 code=0 的正常回包（message 里是「让对方先登录一次再加」那句话），
// 界面就地显示即可，不要弹成故障提示。
export function lookupCloudMember(projectId, identifier) {
  return request({
    url: `/api/cloud/projects/${projectId}/members/lookup?identifier=${encodeURIComponent(identifier || '')}`,
    method: 'GET',
  })
}

// identifier：同事的手机号或邮箱（律师不知道对方在案件库里的账号名，那是桥接自动生成的）。
// 用户名仍可传，服务端把它当兜底。
export function addCloudMember(projectId, identifier, role) {
  return request({ url: `/api/cloud/projects/${projectId}/members`, method: 'POST',
    data: { identifier, role } })
}

// ==================== 记忆同步（Phase A 桌面配置 UI）====================
// repoKey：user-{userId}-memory / project-{projectId}-memory。
// 凭据只写不读：status 只回打码后的 secretMasked；保存时 secret 留空表示沿用已存令牌。

export function getMemorySyncStatus(repoKey) {
  return request({ url: `/api/memory-sync/${repoKey}/status`, method: 'GET' })
}

export function setMemorySyncRemote(repoKey, { url, username, secret }) {
  return request({ url: `/api/memory-sync/${repoKey}/remote`, method: 'POST',
    data: { url, username, secret } })
}

export function removeMemorySyncRemote(repoKey) {
  return request({ url: `/api/memory-sync/${repoKey}/remote`, method: 'DELETE' })
}

export function syncMemoryNow(repoKey) {
  return request({ url: `/api/memory-sync/${repoKey}/sync`, method: 'POST' })
}


// ==================== 诉讼可视化（litviz） ====================

/** 出图环境自检：{available, reason, python, graphviz}。graphviz 只影响流程图一种布局。 */
export function getLitigationVisualStatus() {
  return request({ url: '/api/litigation-visual/status', method: 'GET' })
}

/** 本项目已生成的图（图廊）。识别靠后端的 wpsFileId 标记，用户自己放的 svg 不会混进来。 */
export function getLitigationDiagrams(projectId) {
  return request({ url: `/api/litigation-visual/projects/${projectId}/diagrams`, method: 'GET' })
}

/** 换视觉模式重画。用存下来的语义地图，内容一个字不会变。 */
export function restyleLitigationDiagram(projectId, folderId, mode) {
  return request({
    url: `/api/litigation-visual/projects/${projectId}/restyle`,
    method: 'POST',
    data: { folderId, mode },
    header: { 'Content-Type': 'application/json' }
  })
}

/** 取「开始出图」要发给 AI 的那句话（触发词由服务端保证在正文里）。 */
export function getLitigationKickoffPrompt(projectId, payload) {
  return request({
    url: `/api/litigation-visual/projects/${projectId}/kickoff`,
    method: 'POST',
    data: payload,
    header: { 'Content-Type': 'application/json' }
  })
}

/**
 * 保存内嵌 draw.io 里改完的图。后端一次同步三份产物（.drawio / .svg / .png）——
 * 只写回 .drawio 的话，用户改完图去插图，插进文书的还是旧的那张。
 * @param payload {{xml:string, svg:string}} svg 留空则只存 XML
 */
export function saveDrawioDiagram(projectId, fileId, payload) {
  return request({
    url: `/api/litigation-visual/projects/${projectId}/drawio/${fileId}`,
    method: 'POST',
    data: payload,
    header: { 'Content-Type': 'application/json' }
  })
}
