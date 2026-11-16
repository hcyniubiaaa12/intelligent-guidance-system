/**
 * 出生日期选择器的纯规则（零依赖、可单独验）。
 *
 * 业内的生日选择器都用完整日历；本系统**只保存年份**（`user_health_profile.birth_year`），
 * 所以这里把「日历怎么排」与「选中值怎么换算成年」收在一处：
 * - 日历格：周日起始的 6×7 网格，补齐上下月（上下月的格子不可点，避免"点错月份"）；
 * - 未来日期禁用：出生日期不可能晚于今天；
 * - `toBirthYear()`：把 `YYYY-MM-DD` 换算成服务端要的年份；越界返回 null。
 *
 * 页面与组件都只调这里的函数，不各自再写一遍月份天数/闰年逻辑。
 */

const WEEK_LABELS = ['日', '一', '二', '三', '四', '五', '六']

/** 日期字符串 → { year, month, day }；非法返回 null。month 为 1–12。 */
export function parseDate(value) {
  if (typeof value !== 'string') return null
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value.trim())
  if (!m) return null
  const year = Number(m[1])
  const month = Number(m[2])
  const day = Number(m[3])
  if (month < 1 || month > 12) return null
  if (day < 1 || day > daysInMonth(year, month)) return null
  return { year, month, day }
}

/** 数字 → 两位字符串；YYYY-MM-DD 的月/日补齐 */
function pad2(n) {
  return String(n).padStart(2, '0')
}

/** 年月字符串 → { year, month }；非法返回 null。month 为 1–12。 */
export function parseMonth(value) {
  if (typeof value !== 'string') return null
  const m = /^(\d{4})-(\d{2})$/.exec(value.trim())
  if (!m) return null
  const year = Number(m[1])
  const month = Number(m[2])
  if (month < 1 || month > 12) return null
  return { year, month }
}

/** { year, month } → `YYYY-MM`；非法返回空串 */
export function formatMonth(year, month) {
  if (!Number.isInteger(year) || !Number.isInteger(month)) return ''
  if (month < 1 || month > 12) return ''
  return `${year}-${pad2(month)}`
}

/** 今天所在月份的 `YYYY-MM` */
export function todayMonth(now = new Date()) {
  return formatMonth(now.getFullYear(), now.getMonth() + 1)
}

/** 年月展示文案：「2024 年 3 月」；非法返回空串 */
export function formatMonthDisplay(value) {
  const parsed = parseMonth(value)
  if (!parsed) return ''
  return `${parsed.year} 年 ${parsed.month} 月`
}

/**
 * 年月**紧凑**展示：`2024-03`；非法返回空串。
 *
 * <p>只给单行条目里的紧凑选择器用（`PatientDatePicker` 的 `compact`）。一行里要同时放下
 * 药名、开始、结束、至今、删除，「2024 年 3 月」放不下两次——而年月本来就是零填充的
 * `YYYY-MM`，等宽数字下比中文还好对。面板里照旧写「2024 年 3 月」，那是给人读的句子。
 */
export function formatMonthCompact(value) {
  const parsed = parseMonth(value)
  if (!parsed) return ''
  return `${parsed.year}-${pad2(parsed.month)}`
}

/**
 * 年月是否落在可选范围内（两端含）。`YYYY-MM` 零填充，字符串比较即时间顺序。
 * 空 = 未填，永远算范围外；min/max 为空表示那一端不设限。
 */
export function monthInRange(value, min = '', max = '') {
  if (!parseMonth(value)) return false
  if (max && value > max) return false
  if (min && value < min) return false
  return true
}

/** { year, month, day } → `YYYY-MM-DD`；非法返回空串 */
export function formatDate(year, month, day) {
  if (!Number.isInteger(year) || !Number.isInteger(month) || !Number.isInteger(day)) return ''
  if (month < 1 || month > 12) return ''
  if (day < 1 || day > daysInMonth(year, month)) return ''
  return `${year}-${pad2(month)}-${pad2(day)}`
}

/** 公历闰年：能被 4 整除且不能被 100 整除，或能被 400 整除 */
export function isLeapYear(year) {
  return (year % 4 === 0 && year % 100 !== 0) || year % 400 === 0
}

/** 某年某月的天数（month 为 1–12） */
export function daysInMonth(year, month) {
  if (!Number.isInteger(year) || !Number.isInteger(month) || month < 1 || month > 12) return 0
  return [31, isLeapYear(year) ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31][month - 1]
}

/** 月份平移（跨年自动进位/借位），返回 { year, month } */
export function shiftMonth(year, month, delta) {
  const total = year * 12 + (month - 1) + delta
  return { year: Math.floor(total / 12), month: (total % 12 + 12) % 12 + 1 }
}

/**
 * 生成日历网格（6 行 × 7 列，周日起始）。
 *
 * 每格 = { day, date, inMonth }：
 * - `inMonth=false` 的上下月补位格只负责排满 42 格，页面不渲染文字、不可点；
 * - `date` 是 `YYYY-MM-DD`，直接作为选中值。
 */
export function buildMonthGrid(year, month) {
  const firstWeekday = new Date(year, month - 1, 1).getDay() // 0 = 周日
  const total = daysInMonth(year, month)
  const cells = []
  for (let i = 0; i < 42; i++) {
    const day = i - firstWeekday + 1
    if (day >= 1 && day <= total) {
      cells.push({ day, date: formatDate(year, month, day), inMonth: true })
    } else {
      cells.push({ day: null, date: '', inMonth: false })
    }
  }
  return cells
}

/** 今天（本地时区）的 `YYYY-MM-DD` */
export function todayDate(now = new Date()) {
  return formatDate(now.getFullYear(), now.getMonth() + 1, now.getDate())
}

/**
 * 出生日期 → 服务端要的出生年。
 * 空 = 未填（返回 null）；非法 / 早于 1900 / 晚于今天所在年 → null（调用方给可展示错误）。
 */
export function toBirthYear(dateValue, now = new Date()) {
  const parsed = parseDate(dateValue)
  if (!parsed) return null
  if (parsed.year < 1900 || parsed.year > now.getFullYear()) return null
  // 同一年但日期在未来：生日不能在未来
  if (formatDate(parsed.year, parsed.month, parsed.day) > todayDate(now)) return null
  return parsed.year
}

/** 出生年 → 日历初始值（只知年份时按 1 月 1 日打开；页面用 yearOnly 只显示年份） */
export function fromBirthYear(birthYear) {
  const year = Number(birthYear)
  if (!Number.isInteger(year) || year < 1900 || year > new Date().getFullYear()) return ''
  return formatDate(year, 1, 1)
}

/** 展示文案：yearOnly 时只写年份（服务端只存年份，不假装知道月日） */
export function formatDisplay(dateValue, yearOnly = false) {
  const parsed = parseDate(dateValue)
  if (!parsed) return ''
  if (yearOnly) return `${parsed.year} 年`
  return `${parsed.year} 年 ${parsed.month} 月 ${parsed.day} 日`
}

/** 星期抬头（周日起始） */
export const WEEKDAY_LABELS = WEEK_LABELS

/** 年份网格每页 12 年 */
export const YEAR_PAGE_SIZE = 12

/** 年份网格某一页的起止（含） */
export function yearPageStart(year) {
  return Math.floor(year / YEAR_PAGE_SIZE) * YEAR_PAGE_SIZE
}
