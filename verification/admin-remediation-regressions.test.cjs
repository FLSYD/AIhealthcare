// 管理页筛选入口的回归：执行真实SFC script和分页逻辑，仅替换框架生命周期/API边界。
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const assert = require('node:assert/strict')
const { test } = require('node:test')
const root = path.resolve(__dirname, '../admin-web/src')
const stripImports = value => value.replace(/^import .*$/gm, '').replace(/\bexport /g, '')
function evaluate(source, globals, names) {
  const module = { exports: {} }
  vm.runInNewContext(stripImports(source) + `\nmodule.exports={${names.join(',')}}`, {
    module, console, Date, Intl, Array, Object, Number,
    ref: value => ({ value }), reactive: value => ({ ...value }),
    computed: fn => ({ get value() { return fn() } }),
    onMounted() {}, onBeforeUnmount() {}, nextTick: callback => Promise.resolve().then(callback),
    useStatusRefresh() {}, ...globals
  })
  return module.exports
}
function loadPage(file, names) {
  const requests = []
  const api = { async get(url, options) { requests.push({ url, params: options.params }); return { list: [], total: 0 } } }
  const paged = evaluate(fs.readFileSync(path.join(root, 'lib/usePagedList.js'), 'utf8'), { api }, ['usePagedList'])
  const dates = evaluate(fs.readFileSync(path.join(root, 'lib/scheduleFilters.js'), 'utf8'), {}, ['hospitalToday', 'scheduleQuery'])
  const source = fs.readFileSync(path.join(root, file), 'utf8').match(/<script setup>([\s\S]*?)<\/script>/)[1]
  const page = evaluate(source, { ...paged, ...dates, api, loadAll() {}, confirmAction() {}, runAction() {} }, names)
  return { page, requests, last: () => requests.at(-1).params, today: dates.hospitalToday() }
}
test('管理员查询提交日期与医生，后台刷新保留已提交快照，清空日期恢复默认范围', async () => {
  const { page, last, today } = loadPage('views/admin/SchedulesPage.vue', ['dateFilter', 'filters', 'searchSchedules', 'load'])
  page.dateFilter.value = '2099-11-20'; page.filters.doctorId = 7
  await page.load({ background: true }); assert.strictEqual(last().startDate, today); assert.strictEqual(last().doctorId, null)
  await page.searchSchedules(); assert.strictEqual(last().startDate, '2099-11-20'); assert.strictEqual(last().endDate, '2099-11-20'); assert.strictEqual(last().doctorId, 7)
  page.dateFilter.value = '2099-11-21'; page.filters.doctorId = 8
  await page.load({ background: true }); assert.strictEqual(last().startDate, '2099-11-20'); assert.strictEqual(last().doctorId, 7)
  await page.searchSchedules(); assert.strictEqual(last().startDate, '2099-11-21'); assert.strictEqual(last().doctorId, 8)
  page.dateFilter.value = ''; await page.searchSchedules(); assert.strictEqual(last().startDate, today); assert.strictEqual(last().endDate, undefined)
})
test('医生查询后重置清空日期与实际请求', async () => {
  const { page, last } = loadPage('views/doctor/SchedulesPage.vue', ['dateRange', 'applyFilters', 'resetFilters'])
  page.dateRange.value = ['2099-11-20', '2099-11-21']; await page.applyFilters()
  assert.strictEqual(last().startDate, '2099-11-20'); assert.strictEqual(last().endDate, '2099-11-21')
  await page.resetFilters(); assert.strictEqual(last().startDate, undefined); assert.strictEqual(last().endDate, undefined)
})
test('医生清空日期控件返回null时仍可提交空范围', async () => {
  const { page, last } = loadPage('views/doctor/SchedulesPage.vue', ['dateRange', 'applyFilters'])
  page.dateRange.value = ['2099-11-20', '2099-11-21']; await page.applyFilters()
  page.dateRange.value = null; await page.applyFilters()
  assert.strictEqual(last().startDate, undefined); assert.strictEqual(last().endDate, undefined)
})
test('无效医生日期范围不污染后台已提交条件', async () => {
  const { page, last, requests } = loadPage('views/doctor/SchedulesPage.vue', ['dateRange', 'applyFilters', 'load', 'filterError'])
  page.dateRange.value = ['2099-11-20', '2099-11-21']; await page.applyFilters(); const before = requests.length
  page.dateRange.value = ['2099-12-20', '2099-11-01']; await page.applyFilters()
  assert.strictEqual(requests.length, before); assert.ok(page.filterError.value)
  await page.load({ background: true }); assert.strictEqual(last().startDate, '2099-11-20'); assert.strictEqual(last().endDate, '2099-11-21')
})
