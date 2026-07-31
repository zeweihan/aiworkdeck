// 统一的 API 封装层
// 说明：
// - 所有网络请求都应通过这里发起，组件内禁止直接写 URL。
// - 后端基础地址通过环境变量配置，便于本地 / Sealos / 阿里云等环境切换。

// 导入认证工具
import { getAuthHeaders, getSessionId } from '@/utils/auth.js'

/**
 * 功能未配置时的统一引导（#18 T7）。
 * 调用方在 catch 到 err.featureNotConfigured 时调用，弹出"去设置"引导而非通用报错。
 * @param {Error} err request() reject 出的错误，带 featureNotConfigured / feature / message
 */
export function promptFeatureNotConfigured(err) {
  const message = (err && err.message) || '该功能尚未配置，请在设置中补充';
  uni.showModal({
    title: '功能未配置 / Not configured',
    content: message,
    confirmText: '去设置',
    cancelText: '稍后',
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
            `请求失败 (${status})`;
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
          } else if (res.data.code === 4001) {
            // 功能未配置（#18 T7）：reject 时带 featureNotConfigured 标记，
            // 由调用方决定如何引导（弹"去设置" / 降级为只读），避免在拦截器
            // 层强弹全局弹窗导致打开文档时反复打扰。
            const msg = res.data.message || '该功能尚未配置，请在设置中补充';
            console.warn('功能未配置:', { feature: res.data.feature, message: msg });
            const err = new Error(msg);
            err.featureNotConfigured = true;
            err.feature = res.data.feature || '';
            reject(err);
          } else {
            // 业务失败：code=1 或其他非0值
            const errorMessage = res.data.message || '服务异常，请稍后重试'
            console.error('业务错误:', {
              code: res.data.code,
              message: errorMessage,
              data: res.data.data,
              fullResponse: res.data
            })

            // 特殊处理：未登录错误（code=1且message包含"登录"关键字）
            if (errorMessage.includes('登录') || errorMessage.includes('未授权') || errorMessage.includes('请先')) {
              console.warn('检测到未登录状态，准备跳转到登录页');
              // 清除本地存储的session信息
              try {
                uni.removeStorageSync('sessionId');
                uni.removeStorageSync('userId');
              } catch (e) {
                console.warn('清除登录信息失败:', e);
              }

              // 跳转到登录页
              uni.reLaunch({
                url: '/pages/login/login',
                success: () => {
                  console.log('已跳转到登录页');
                  uni.showToast({
                    title: '登录已过期，请重新登录',
                    icon: 'none',
                    duration: 2000
                  });
                },
                fail: (err) => {
                  console.error('跳转到登录页失败:', err);
                }
              });
            }

            reject(new Error(errorMessage));
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
export function fetchCompanyBasicInfo(payload) {
  return request({
    url: '/api/external/company/basic',
    method: 'POST',
    data: payload,
    header: {
      'Content-Type': 'application/json',
    },
  });
}

// ===================== AI 助手相关 API =====================

/**
 * 项目内 AI 对话
 * payload: { projectId: string|number, message: string, model?: string }
 */
export function aiChat(payload) {
  return request({
    url: '/api/ai/chat',
    method: 'POST',
    data: {
      projectId: String(payload.projectId),
      message: payload.message,
      context: payload.context || null,
      model: payload.model || null,
      conversationId: payload.conversationId || null
    },
    header: {
      'Content-Type': 'application/json',
    },
    timeout: 300000, // Increase timeout to 300s for local LLM
  });
}

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

// 获取 AI 公共配置（如默认供应商）
export function getAiConfig() {
  return request({
    url: '/api/ai/config',
    method: 'GET'
  });
}

// 获取可用 AI 助手列表
export function getAssistants() {
  return request({
    url: '/api/ai/assistants',
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

// 重新扫描 plugins/ 目录（仅管理员）
export function rescanPlugins() {
  return request({
    url: '/api/plugins/rescan',
    method: 'POST'
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

// 查询首次运行向导状态（Epic #18 T4）：{ code, initialized }
export function getWizardStatus() {
  return request({
    url: '/api/admin/wizard',
    method: 'GET',
  });
}

// 重置首次运行向导（仅管理员）：completed 置 false，之后可重新走一遍向导
export function resetWizard() {
  return request({
    url: '/api/admin/wizard/reset',
    method: 'POST',
  });
}

// 提交首次运行向导（仅未初始化时可调用，payload 结构同 saveAdminConfig）
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
export function login(username, password) {
  return request({
    url: '/api/auth/login',
    method: 'POST',
    data: {
      username,
      password,
    },
    header: {
      'Content-Type': 'application/json',
    },
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
              reject(new Error(data.message || '上传失败'))
            }
          } catch (e) {
            reject(new Error('解析响应失败'))
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

// 文档-文件关联（WPS 选区超链接）
export function createDocFileLink(projectId, payload) {
  return request({
    url: `/api/projects/${projectId}/doc-links`,
    method: 'POST',
    data: payload,
    header: {
      'Content-Type': 'application/json',
    },
  })
}

export function getDocFileLink(projectId, linkKey) {
  return request({
    url: `/api/projects/${projectId}/doc-links/${encodeURIComponent(linkKey)}`,
    method: 'GET',
  })
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
            reject(new Error(data.message || '上传失败'))
          }
        } catch (e) {
          reject(new Error('解析响应失败'))
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
export function logActivity(actionType, targetId, targetName, duration, metaInfo) {
  return request({
    url: '/api/activity/log',
    method: 'POST',
    data: {
      actionType,
      targetId,
      targetName,
      duration,
      metaInfo
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
  aiChat,
  exportAiDocx,
  fetchCompanyBasicInfo,
  createProject,
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
  searchProjectContent
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
    throw new Error((j && j.message) || '读取版本文件失败');
  }
  if (!resp.ok) throw new Error('读取版本文件失败');
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

export function shareProjectToCloud(projectId, connectionId) {
  return request({ url: `/api/cloud/projects/${projectId}/share`, method: 'POST',
    data: { connectionId } })
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

