// 出生日期选择器的纯规则自检（零依赖，随 `npm run check` 常驻）。
// 日历错一天就是生日错一天；服务端只保存年份，换算规则更不能靠肉眼。
import assert from 'node:assert/strict'
import {
  YEAR_PAGE_SIZE,
  buildMonthGrid,
  daysInMonth,
  formatDate,
  formatDisplay,
  formatMonth,
  formatMonthDisplay,
  fromBirthYear,
  isLeapYear,
  monthInRange,
  parseDate,
  parseMonth,
  shiftMonth,
  toBirthYear,
  todayMonth,
  yearPageStart
} from '../src/utils/datePicker.js'

let count = 0
function check(name, fn) {
  fn()
  count++
  console.log(`✓ ${name}`)
}

const NOW = new Date(2026, 10, 14) // 2026-11-14（本地时区）

check('闰年规则：2000 闰、1900 不闰、2024 闰、2023 不闰', () => {
  assert.equal(isLeapYear(2000), true)
  assert.equal(isLeapYear(1900), false)
  assert.equal(isLeapYear(2024), true)
  assert.equal(isLeapYear(2023), false)
})

check('月份天数：2 月随闰年、4/6/9/11 为 30 天', () => {
  assert.equal(daysInMonth(2024, 2), 29)
  assert.equal(daysInMonth(2023, 2), 28)
  assert.equal(daysInMonth(2024, 4), 30)
  assert.equal(daysInMonth(2024, 1), 31)
})

check('日期解析与格式化：非法月/日/格式一律 null 或空串', () => {
  assert.deepEqual(parseDate('1990-05-12'), { year: 1990, month: 5, day: 12 })
  assert.equal(parseDate('1990-02-30'), null)
  assert.equal(parseDate('1990-13-01'), null)
  assert.equal(parseDate('1990-5-1'), null)
  assert.equal(formatDate(1990, 5, 12), '1990-05-12')
  assert.equal(formatDate(1990, 2, 30), '')
})

check('月份平移：跨年进位与借位', () => {
  assert.deepEqual(shiftMonth(2024, 1, -1), { year: 2023, month: 12 })
  assert.deepEqual(shiftMonth(2024, 12, 1), { year: 2025, month: 1 })
  assert.deepEqual(shiftMonth(2024, 6, -18), { year: 2022, month: 12 })
})

check('日历网格：42 格、周日起始、1 号落在正确的星期', () => {
  const cells = buildMonthGrid(2024, 2) // 2024-02-01 是周四
  assert.equal(cells.length, 42)
  assert.equal(cells.filter((c) => c.inMonth).length, 29)
  const first = cells.find((c) => c.date === '2024-02-01')
  assert.equal(cells.indexOf(first), 4) // 周日 0、周一 1、周二 2、周三 3、周四 4
  assert.equal(cells[0].inMonth, false) // 1 号前的补位格
})

check('出生年换算：正常、闰日、空值', () => {
  assert.equal(toBirthYear('1990-05-12', NOW), 1990)
  assert.equal(toBirthYear('2000-02-29', NOW), 2000)
  assert.equal(toBirthYear('', NOW), null)
})

check('出生年换算：1900 以前、晚于今天所在年、今年未来日期都拒绝', () => {
  assert.equal(toBirthYear('1899-12-31', NOW), null)
  assert.equal(toBirthYear('2027-01-01', NOW), null)
  assert.equal(toBirthYear('2026-12-01', NOW), null) // 今年但未来
  assert.equal(toBirthYear('2026-11-14', NOW), 2026) // 今天可以
})

check('年份 ↔ 日历值：只知年份时按 1 月 1 日打开，展示只写年份', () => {
  assert.equal(fromBirthYear(1980), '1980-01-01')
  assert.equal(fromBirthYear(''), '')
  assert.equal(fromBirthYear(1800), '')
  assert.equal(formatDisplay('1980-01-01', true), '1980 年')
  assert.equal(formatDisplay('1980-05-12', false), '1980 年 5 月 12 日')
  assert.equal(formatDisplay('', false), '')
})

check('年份网格分页：12 年一页、页起点向下取整', () => {
  assert.equal(YEAR_PAGE_SIZE, 12)
  assert.equal(yearPageStart(1990), 1980)
  assert.equal(yearPageStart(1980), 1980)
  assert.equal(yearPageStart(1979), 1968)
})

check('年月解析与格式化：YYYY-MM、非法值一律 null 或空串', () => {
  assert.deepEqual(parseMonth('2024-03'), { year: 2024, month: 3 })
  assert.equal(parseMonth('2024-3'), null)
  assert.equal(parseMonth('2024-13'), null)
  assert.equal(parseMonth('2024-03-01'), null)
  assert.equal(parseMonth(''), null)
  assert.equal(formatMonth(2024, 3), '2024-03')
  assert.equal(formatMonth(2024, 13), '')
  assert.equal(formatMonthDisplay('2024-03'), '2024 年 3 月')
  assert.equal(formatMonthDisplay('2024-3'), '')
})

check('年月范围：两端含、空 min/max 不设限、非法值永远在范围外', () => {
  assert.equal(monthInRange('2024-03', '2024-01', '2024-06'), true)
  assert.equal(monthInRange('2024-01', '2024-01', '2024-06'), true)
  assert.equal(monthInRange('2024-06', '2024-01', '2024-06'), true)
  assert.equal(monthInRange('2023-12', '2024-01', '2024-06'), false)
  assert.equal(monthInRange('2024-07', '2024-01', '2024-06'), false)
  assert.equal(monthInRange('2024-07'), true)
  assert.equal(monthInRange('2024-03', '2024-04'), false)
  assert.equal(monthInRange('', '', '2026-11'), false)
  assert.equal(monthInRange('2024-3', '', '2026-11'), false)
})

check('今天所在月份', () => {
  assert.equal(todayMonth(NOW), '2026-11')
})

console.log(`\n✓ 日期/年月选择器纯规则 ${count} 组断言全部通过`)
