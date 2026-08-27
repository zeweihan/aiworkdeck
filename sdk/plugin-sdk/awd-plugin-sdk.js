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
 * v2.5 新增（宿主 0.27 起；老宿主返回 unknown_method，插件要能降级）：
 *   tools.invoke  { name, args? }     -> { output }        直调本插件 manifest 声明的 JAR 工具；
 *                                        output 是工具原始字符串输出（通常为 JSON）。权限/配额/项目归属
 *                                        由宿主端点按 AI 链路同一套规则校验，工具名不属于本插件即 invoke_failed
 *   chat.send     { prompt }          -> {}                把 prompt 作为可见的用户消息发进 AI 对话
 *                                        （与启动面板快捷按钮同一条路），上限 4000 字
 *   ui.openFile   { path }            -> {}                需 file_read；把项目文件打开到工作台中栏
 *
 * 错误码：permission_denied（manifest 未声明所需权限）、unknown_method（宿主不认识的方法）、
 *   anchor_ambiguous（引文 0 或多处命中 / anchor 形状不对）、no_selection（要求选区但当前没有）、
 *   not_found（链接或文件不存在）、no_active_document（当前没有打开的 Word 文档）、
 *   invalid_params（参数缺失或形状不对）、invoke_failed（工具执行被拒或出错）。
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
      resolveReady(awd.context);
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

  function call(method, params) {
    return new Promise(function (resolve, reject) {
      var id = ++seq;
      pending[id] = { resolve: resolve, reject: reject };
      post({ awd: PROTOCOL, type: 'call', seq: id, method: method, params: params || {} });
    });
  }

  var awd = {
    /** SDK 版本，与桥协议版本无关 */
    version: '1.1.0',
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
    evidence: {
      /** -> { linkKey, targetIds } */
      link: function (params) { return call('evidence.link', params || {}); },
      /** -> { links: [...] } 原始 result，不解包 */
      list: function (params) { return call('evidence.list', params || {}); },
      /** -> {} */
      locate: function (params) { return call('evidence.locate', params || {}); }
    }
  };

  global.awd = awd;
})(window);
