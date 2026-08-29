/**
 * AI WorkDeck 插件 SDK v1 —— postMessage 桥。
 *
 * 源头在桌面仓 sdk/plugin-sdk/，此为随模板分发的副本。
 * 契约（桌面端宿主按同一份实现，勿单方面改动）：
 *
 *   握手  宿主 -> 插件   { awd: 1, type: "init", context: {...} }
 *   请求  插件 -> 宿主   { awd: 1, type: "call", seq, method, params }
 *   响应  宿主 -> 插件   { awd: 1, type: "result", seq, ok, result | error: { code, message } }
 *
 * v1 方法：
 *   context.get   {}                  -> { pluginId, projectId, language, theme }
 *   files.list    {}                  -> { files: [{ path, name, size }] }   需 file_read
 *   files.read    { path }            -> { path, content, truncated }        需 file_read，文本上限 5 MB
 *   ui.toast      { message }         -> {}
 *   storage.get   { key }             -> { key, value }    插件级 KV，value 为 null 表示不存在
 *   storage.set   { key, value }      -> {}                插件级 KV，总量上限 64 KB
 *   evidence.link   { anchor: { selection: true } | { quote }, docPath?, targets: [{ path, locator?, relation?, method?, note? }] }
 *                                     -> { linkKey, targetIds }  需 editor；在当前 Word 文档的选区/引文上建底稿关联
 *   evidence.list   { docPath?, path?, sectionPath?, status? }
 *                                     -> { links: [{ linkKey, docPath, anchorText, sectionPath, status, targets: [{ targetId, path, locator, relation, method }] }] }  需 file_read
 *   evidence.locate { linkKey, targetId? }
 *                                     -> {}                需 editor；有 targetId 打开底稿定位，否则跳到文档里的锚点
 *
 * v2.6 新增（宿主 0.27.4 起）——主题通道，照 VS Code 给 webview 注入主题的机制：
 *   推送  宿主 -> 插件   { awd: 1, type: "theme", theme: "light"|"dark", tokens: { "--awd-*": "..." } }
 *   init 的 context 也带 theme 与 themeTokens 两个字段（老宿主只有 theme 字符串，
 *   没有 themeTokens——SDK 会降级为只挂 data-theme，不注入变量）。
 *   SDK 收到即自动：documentElement 挂 data-theme="light|dark"、body 挂
 *   awd-theme-light / awd-theme-dark class、把 tokens 逐个写成 CSS 自定义属性。
 *   插件 CSS 因此可以直接用 var(--awd-surface) 这类语义令牌，零 JS 跟随主题；
 *   需要脚本联动的用 awd.theme.onChange(cb)。
 *
 * v2.5 新增（宿主 0.27 起；老宿主返回 unknown_method，插件要能降级）：
 *   tools.invoke  { name, args? }     -> { output }        直调本插件 manifest 声明的 JAR 工具；
 *                                        output 是工具原始字符串输出（通常为 JSON）。权限/配额/项目归属
 *                                        由宿主端点按 AI 链路同一套规则校验，工具名不属于本插件即 invoke_failed
 *   chat.send     { prompt }          -> {}                把 prompt 作为可见的用户消息发进 AI 对话
 *                                        （与启动面板快捷按钮同一条路），上限 4000 字
 *   ui.openFile   { path }            -> {}                需 file_read；把项目文件打开到工作台中栏
 *
 * v2.7 新增（宿主 0.28 起；老宿主对新方法返回 unknown_method，SDK 已按下述说明降级）：
 *   doc.exec      { action, params? }  -> { result }        需 editor；对当前聚焦文档执行编辑原语，
 *                                        action/params 与 AI 工具面的下发名同一套（doc_/sheet_/slide_ 全集
 *                                        的安全子集，白名单外返回 action_not_allowed）。写入走修订（Writer），
 *                                        署名 AI WorkDeck，用户可逐条接受/拒绝
 *   doc.active    {}                   -> { fileId, kind }  需 editor；当前聚焦文档（kind: writer|calc|impress），
 *                                        没有打开的文档时 fileId 为 null
 *   events.subscribe   { events: [..] } -> { subscribed }   订阅宿主事件（见下）；权限不足的事件名被静默剔除，
 *                                        以回声的 subscribed 集合为准
 *   events.unsubscribe { events: [..] } -> { subscribed }
 *   ai.request    { prompt, system?, purpose? } -> { text, modelId }
 *                                        需 ai 权限；经平台 Credits 通道调辅助模型（插件免带 Key），
 *                                        prompt+system 合计上限 16000 字符，每插件 10 次/分钟。
 *                                        要工具、要落文档、要让用户看见过程的场景走 chat.send，不走这条
 *
 *   事件推送  宿主 -> 插件   { awd: 1, type: "event", event, data }
 *   首批事件：files.changed（需 file_read）/ selection.changed（需 editor）/ project.switched。
 *   payload 刻意为空——事件是「该重拉了」的信号，数据由插件按各自权限的方法拉取。
 *   用 awd.events.on(name, cb) 即可（自动订阅/退订；老宿主上 on 照常返回退订函数，只是永不触发）。
 *
 * 错误码：permission_denied（manifest 未声明所需权限）、unknown_method（宿主不认识的方法）、
 *   anchor_ambiguous（引文 0 或多处命中 / anchor 形状不对）、no_selection（要求选区但当前没有）、
 *   not_found（链接或文件不存在）、no_active_document（当前没有打开的 Word 文档）、
 *   invalid_params（参数缺失或形状不对）、invoke_failed（工具执行被拒或出错）、
 *   action_not_allowed（doc.exec 的原语不对插件开放）、ai_failed（模型调用失败）、
 *   quota_exceeded（存储/字数/频次超限）、experimental_not_allowed（x- 实验方法仅对 dev 安装开放）。
 *
 * 重要：本文件必须用普通同步 <script> 引入，且排在业务脚本之前——
 * 宿主在 iframe load 后立刻发 init，晚注册监听会错过握手，ready() 将永远挂起。
 */
(function (global) {
  'use strict';

  var PROTOCOL = 1;
  var seq = 0;
  var pending = {};
  var resolveReady;
  var readyPromise = new Promise(function (resolve) { resolveReady = resolve; });

  function post(msg) {
    // 插件运行在 opaque origin 的 sandbox iframe 里，拿不到宿主的真实 origin，
    // 只能用 '*'；反向的来源校验由宿主端（校验 event.source 是本 iframe）负责。
    global.parent.postMessage(msg, '*');
  }

  global.addEventListener('message', function (event) {
    if (event.source !== global.parent) return;
    var msg = event.data;
    if (!msg || msg.awd !== PROTOCOL) return;

    if (msg.type === 'init') {
      awd.context = msg.context || {};
      applyTheme(awd.context.theme, awd.context.themeTokens);
      resolveReady(awd.context);
      return;
    }

    if (msg.type === 'theme') {
      applyTheme(msg.theme, msg.tokens);
      return;
    }

    if (msg.type === 'event') {
      var handlers = eventListeners[msg.event];
      if (handlers) {
        handlers.slice().forEach(function (cb) {
          try { cb(msg.data || {}); } catch (e) { /* 插件回调抛错不打断其余 */ }
        });
      }
      return;
    }

    if (msg.type === 'result') {
      var entry = pending[msg.seq];
      if (!entry) return;
      delete pending[msg.seq];
      if (msg.ok) {
        entry.resolve(msg.result);
      } else {
        var err = new Error((msg.error && msg.error.message) || '调用失败');
        err.code = (msg.error && msg.error.code) || 'unknown_error';
        entry.reject(err);
      }
    }
  });

  // ---- 主题（v2.6）----------------------------------------------------------
  // 自动应用 + 回调通知。tokens 缺席（老宿主）时只挂 data-theme/class，
  // 插件的 var(--awd-*) 走自己 CSS 里的 fallback 值。
  var themeState = { mode: 'light', tokens: {} };
  var themeListeners = [];
  function applyTheme(mode, tokens) {
    themeState.mode = mode === 'dark' ? 'dark' : 'light';
    if (tokens && typeof tokens === 'object') themeState.tokens = tokens;
    try {
      var rootEl = document.documentElement;
      rootEl.setAttribute('data-theme', themeState.mode);
      Object.keys(themeState.tokens).forEach(function (k) {
        if (k.indexOf('--') === 0) rootEl.style.setProperty(k, String(themeState.tokens[k]));
      });
      if (document.body) {
        document.body.classList.toggle('awd-theme-dark', themeState.mode === 'dark');
        document.body.classList.toggle('awd-theme-light', themeState.mode !== 'dark');
      }
    } catch (e) { /* 无 DOM 环境静默 */ }
    themeListeners.forEach(function (cb) {
      try { cb(themeState.mode, themeState.tokens); } catch (e) { /* 插件回调抛错不打断其余 */ }
    });
  }

  function call(method, params) {
    return new Promise(function (resolve, reject) {
      var id = ++seq;
      pending[id] = { resolve: resolve, reject: reject };
      post({ awd: PROTOCOL, type: 'call', seq: id, method: method, params: params || {} });
    });
  }

  // ---- 事件通道（v2.7）------------------------------------------------------
  // on() 自动向宿主订阅（首个监听者时），退订函数在最后一个监听者移除时自动退订。
  // 老宿主对 events.subscribe 返回 unknown_method：静默吞掉——on 照常工作，只是永不触发，
  // 插件代码因此不需要为宿主版本写条件分支。
  var eventListeners = {};
  function syncSubscribe(method, name) {
    call(method, { events: [name] }).then(null, function () { /* 老宿主 unknown_method，静默 */ });
  }

  var awd = {
    /** SDK 版本，与桥协议版本无关 */
    version: '1.3.0',
    /** 握手拿到的上下文；ready() 之前为 null */
    context: null,
    /** 等待宿主握手，resolve 值即 awd.context */
    ready: function () { return readyPromise; },
    /** 原样调用任意 v1 方法，返回宿主的 result */
    call: call,
    files: {
      /** -> Array<{ path, name, size }> */
      list: function () {
        return call('files.list', {}).then(function (r) { return (r && r.files) || []; });
      },
      /** -> { path, content, truncated } */
      read: function (path) { return call('files.read', { path: path }); }
    },
    ui: {
      toast: function (message) { return call('ui.toast', { message: message }); },
      /** 把项目文件打开到工作台中栏（需 file_read） */
      openFile: function (path) { return call('ui.openFile', { path: path }); }
    },
    tools: {
      /** 直调本插件的 JAR 工具 -> 工具原始字符串输出（自行 JSON.parse） */
      invoke: function (name, args) {
        return call('tools.invoke', { name: name, args: args || {} }).then(function (r) {
          return r && r.output != null ? r.output : '';
        });
      }
    },
    chat: {
      /** 把 prompt 作为可见用户消息发进 AI 对话（起草类动作走这条，不直调工具） */
      send: function (prompt) { return call('chat.send', { prompt: prompt }); }
    },
    storage: {
      /** -> 存过的值，没有则 null */
      get: function (key) {
        return call('storage.get', { key: key }).then(function (r) {
          return r && r.value !== undefined ? r.value : null;
        });
      },
      set: function (key, value) { return call('storage.set', { key: key, value: value }); }
    },
    theme: {
      /** 当前主题：{ mode: 'light'|'dark', tokens: { '--awd-*': '...' } }。
       *  SDK 已自动把 data-theme/class/CSS 变量挂到本页，多数插件写 CSS 即可，
       *  不需要碰这个 API。 */
      get: function () { return { mode: themeState.mode, tokens: themeState.tokens }; },
      /** 主题切换回调 cb(mode, tokens)，返回退订函数。init 时已应用的主题不补发。 */
      onChange: function (cb) {
        if (typeof cb !== 'function') return function () {};
        themeListeners.push(cb);
        return function () {
          var i = themeListeners.indexOf(cb);
          if (i >= 0) themeListeners.splice(i, 1);
        };
      }
    },
    evidence: {
      /** -> { linkKey, targetIds } */
      link: function (params) { return call('evidence.link', params || {}); },
      /** -> { links: [...] } 原始 result，不解包 */
      list: function (params) { return call('evidence.list', params || {}); },
      /** -> {} */
      locate: function (params) { return call('evidence.locate', params || {}); }
    },
    doc: {
      /** 对当前聚焦文档执行编辑原语 -> 原语的原始返回对象（需 editor 权限） */
      exec: function (action, params) {
        return call('doc.exec', { action: action, params: params || {} }).then(function (r) {
          return r ? r.result : {};
        });
      },
      /** -> { fileId, kind }；没有打开的文档时 fileId 为 null（需 editor 权限） */
      active: function () { return call('doc.active', {}); },
      /** 糖衣：读全文 -> 原语原始返回（含段落数组，形状见宿主 get_document_text 文档） */
      getText: function () { return awd.doc.exec('get_document_text', {}); },
      /** 糖衣：读当前选区 */
      getSelection: function () { return awd.doc.exec('get_selection', {}); },
      /** 糖衣：查找文本出现位置 */
      find: function (text) { return awd.doc.exec('find_text_locations', { text: text }); },
      /** 糖衣：光标处插入文本（Writer 走修订，署名 AI WorkDeck） */
      insertText: function (text) { return awd.doc.exec('insert_at_cursor', { text: text }); },
      /** 糖衣：在恰好命中一次的锚点文字上加批注 */
      addComment: function (anchorText, text) {
        return awd.doc.exec('add_comment', { anchorText: anchorText, text: text });
      }
    },
    events: {
      /**
       * 订阅宿主事件：files.changed / selection.changed / project.switched。
       * cb(data) 在宿主推送时触发；返回退订函数。自动向宿主 subscribe/unsubscribe，
       * 老宿主（无事件通道）上照常返回退订函数，只是永不触发。
       */
      on: function (name, cb) {
        if (typeof cb !== 'function' || !name) return function () {};
        var key = String(name);
        if (!eventListeners[key]) {
          eventListeners[key] = [];
          syncSubscribe('events.subscribe', key);
        }
        eventListeners[key].push(cb);
        return function () {
          var handlers = eventListeners[key];
          if (!handlers) return;
          var i = handlers.indexOf(cb);
          if (i >= 0) handlers.splice(i, 1);
          if (handlers.length === 0) {
            delete eventListeners[key];
            syncSubscribe('events.unsubscribe', key);
          }
        };
      }
    },
    ai: {
      /**
       * 面板内的一次性静默推理（需 ai 权限，走平台 Credits 的辅助模型）-> 输出文本。
       * opts 可带 { system, purpose }；要工具/落文档/让用户看见过程的场景用 awd.chat.send。
       */
      request: function (prompt, opts) {
        var p = opts || {};
        return call('ai.request', { prompt: prompt, system: p.system, purpose: p.purpose })
          .then(function (r) { return r && r.text != null ? r.text : ''; });
      }
    }
  };

  global.awd = awd;
})(window);
