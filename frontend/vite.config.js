// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
import { defineConfig } from "vite";
import uni from "@dcloudio/vite-plugin-uni";
// https://vitejs.dev/config/
export default defineConfig({
    plugins: [uni()],
    resolve: {
        alias: {
            "punycode.js": "punycode",
        }
    },
    base: "./",
    server: {
        host: "0.0.0.0", // 允许外部访问
        port: 5173,
        strictPort: false, // 如果端口被占用，自动尝试下一个可用端口
        open: false, // 不自动打开浏览器
        cors: true, // 启用 CORS
        hmr: {
            overlay: true, // 显示错误覆盖层
        },
    },
    // 配置静态资源
    publicDir: "static",
});
