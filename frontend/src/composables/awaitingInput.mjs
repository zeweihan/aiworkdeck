// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 「本轮停在等用户回答」的唯一判据。后端组件未就绪时也用 awaiting_input 收尾
// （reason=component_required），那不是模型反问：输入区不能显示「等你回答」，
// 会话列表也不该标「待回答」。
export function isUserQuestionAwaiting(payload) {
    return !!payload && String(payload.status).toLowerCase() === 'awaiting_input' && payload.reason !== 'component_required'
}
