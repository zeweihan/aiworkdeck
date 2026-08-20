// 节假日/调休标记（chinese-days，MIT，支持 2004-2026 年，超出范围优雅降级为普通工作日）。
//
// getDayDetail(date) 返回 { work, name }：
// - 普通工作日：work=true，name='Thursday' 这类星期几英文名（无逗号）。
// - 普通周末（非法定假期顺延）：work=false，name='Saturday'/'Sunday'（无逗号）。
// - 法定节假日（含落在周末的部分）：work=false，name='National Day,国庆节,3'（含逗号）。
// - 调休补班（周末但因假期顺延要上班）：work=true，name 同样含逗号。
// 用 name 是否含逗号区分「真节假日」与「单纯周末」——两者 work 都可能是 false，
// 但只有前者该显「休」角标，纯周末只吃浅灰底、不挂角标。
import { getDayDetail } from 'chinese-days'

// FullCalendar 对同一个格子会分别调 dayCellClassNames 与 dayCellContent（表头同理），
// 每次渲染每格两次查询；结果按天恒定，用 Map 记住即可（键=本地 YYYY-MM-DD）。
const markCache = new Map()

/**
 * @param {Date} date 本地日期（时分秒不参与判定）
 * @returns {'holiday'|'makeup'|'weekend'|'none'}
 */
export function getDayMarkType(date) {
  const key = date.getFullYear() + '-' + String(date.getMonth() + 1).padStart(2, '0') + '-' + String(date.getDate()).padStart(2, '0')
  const cached = markCache.get(key)
  if (cached) return cached
  const mark = computeMarkType(date)
  markCache.set(key, mark)
  return mark
}

function computeMarkType(date) {
  const dow = date.getDay()
  const isWeekend = dow === 0 || dow === 6
  let detail = null
  try {
    detail = getDayDetail(date)
  } catch (e) {
    detail = null
  }
  if (!detail) return isWeekend ? 'weekend' : 'none'

  const isNamedHoliday = typeof detail.name === 'string' && detail.name.includes(',')
  if (!detail.work && (isNamedHoliday || !isWeekend)) return 'holiday'
  if (detail.work && isWeekend && isNamedHoliday) return 'makeup'
  if (isWeekend) return 'weekend'
  return 'none'
}
