// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// WPS 加载项 JS 入口（由 index.html 包含）：按官方 wpsjs 模板惯例用 document.write
// 依序引入脚本。ribbon 页没有可见 UI，逻辑全在 js/ 下的两个文件里。
document.write("<script language='javascript' src='js/util.js'></script>");
document.write("<script language='javascript' src='js/ribbon.js'></script>");
