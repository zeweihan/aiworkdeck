// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
import {
	createSSRApp
} from "vue";
import App from "./App.vue";
import { recordFrontendError } from "./utils/errorBuffer.js";
import { i18n } from "./i18n/index.js";
// #ifdef H5
import { installUniDialogBridge } from "./utils/dialog.js";
import { installUniToastBridge } from "./utils/toast.js";
// #endif

export function createApp() {
	const app = createSSRApp(App);
	// i18n（EN 版）：locale 由 utils/appLanguage.js 决定；语言切换走整页 reload
	app.use(i18n);
	// #ifdef H5
	// uni.showModal / uni.showActionSheet 转发到应用内对话框 AwdDialog（dev-board#849）：
	// uni-h5 自带的弹窗缺省按钮是写死的英文 OK/Cancel、字体掉到衬线字。
	// 走拦截器而不是改写属性，理由见 utils/dialog.js 顶部注释。小程序端不接管。
	installUniDialogBridge();
	// uni.showToast / hideToast / showLoading / hideLoading 转发到统一 toast 体系
	// AwdToast（dev-board#891）：三种语义一套视觉、多条纵向堆叠不互相覆盖。
	// 同样走拦截器，理由见 utils/toast.js 顶部注释。
	installUniToastBridge();
	// #endif
	
	// 全局错误处理：捕获未处理的 Promise rejection
	if (typeof window !== 'undefined') {
		window.addEventListener('unhandledrejection', (event) => {
			// 只记录真正的错误，忽略一些已知的、不影响功能的错误
			const error = event.reason
			const errorMsg = error?.message || error?.msg || String(error)
			
			// 过滤掉一些已知的、不影响功能的错误
			const ignoredPatterns = [
				'ResizeObserver loop',  // 浏览器已知问题，不影响功能
				'Non-Error promise rejection',  // 某些库的已知问题
			]
			
			const shouldIgnore = ignoredPatterns.some(pattern => 
				errorMsg.includes(pattern)
			)
			
			if (!shouldIgnore) {
				console.error('未处理的 Promise rejection:', {
					reason: error,
					message: errorMsg,
					stack: error?.stack
				})
				// 同时记进环形缓冲，供反馈浮窗附带「最近的报错」——
				// 用户点开反馈时早已离这声报错几十秒，控制台里没人会去翻
				recordFrontendError({ kind: 'unhandledrejection', message: errorMsg, stack: error?.stack })
				// 阻止默认行为（在控制台显示），但我们已经记录了
				// event.preventDefault()
			}
		})
		
		// 全局错误处理：捕获运行时错误
		window.addEventListener('error', (event) => {
			// 过滤掉一些已知的、不影响功能的错误
			const errorMsg = event.message || ''
			const ignoredPatterns = [
				'ResizeObserver loop',
				'Script error',  // 跨域脚本错误，无法获取详细信息
			]
			
			const shouldIgnore = ignoredPatterns.some(pattern => 
				errorMsg.includes(pattern)
			)
			
			if (!shouldIgnore) {
				console.error('全局错误:', {
					message: event.message,
					filename: event.filename,
					lineno: event.lineno,
					colno: event.colno,
					error: event.error
				})
				recordFrontendError({
					kind: 'error',
					message: errorMsg,
					source: (event.filename || '') + ':' + event.lineno + ':' + event.colno,
					stack: event.error?.stack
				})
			}
		})
	}
	
	return {
		app,
	};
}
