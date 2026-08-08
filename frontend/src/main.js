import {
	createSSRApp
} from "vue";
import App from "./App.vue";
import { recordFrontendError } from "./utils/errorBuffer.js";

export function createApp() {
	const app = createSSRApp(App);
	
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
