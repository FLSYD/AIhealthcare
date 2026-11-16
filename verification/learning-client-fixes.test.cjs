const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')

const root = path.resolve(__dirname, '..')
const deferred = () => { let resolve; const promise = new Promise(r => { resolve = r }); return { promise, resolve } }
const flush = () => new Promise(resolve => setImmediate(resolve))

function loadPage(relative, mocks = {}, options = {}) {
  const source = fs.readFileSync(path.join(root, 'wechat-miniprogram/miniprogram', relative), 'utf8')
  let definition
  const timers = new Map()
  let timerId = 0
  const wx = options.wx || {
    showToast() {}, showModal() {}, navigateBack() {}, redirectTo() {}, switchTab() {},
  }
  const defaults = {
    '../../utils/auth': { isLoggedIn: () => true, fetchMe: async () => ({ balance: 100 }) },
    '../../utils/toast': message => { options.messages?.push(String(message)) },
    '../../utils/format': { formatDateTime: () => '', formatFee: value => String(value) },
    '../../utils/adapter': {
      toOrder: value => ({ ...value, fee: value.amount || 20, canPay: true }),
      enrichDepartments: value => value,
      enrichDoctor: value => value,
    },
    '../../utils/pay': {
      PAY_METHODS: [], needsPayDrawer: () => false, resolvePayMode: async () => 'LEGACY',
      mockChannelPay: async () => {}, payNative: async () => {}, loadPayMode: async () => 'MOCK',
      decoratePayMethods: methods => methods, payTipFor: () => '',
    },
    '../../utils/api': {},
    '../../utils/dept-meta': { enrichDepartments: value => value },
    '../../utils/search': { findDepartmentByKeyword: () => null, filterDepartmentsByKeyword: value => value, buildDoctorQuery: () => ({ params: {} }) },
    '../../utils/tab': { setTabBar() {} },
  }
  const sandbox = {
    console, Promise, wx, module: { exports: {} }, exports: {},
    setTimeout(callback, delay) { const id = ++timerId; timers.set(id, { callback, delay }); return id },
    clearTimeout(id) { timers.delete(id) },
    getCurrentPages: () => [{}, {}],
    getApp: () => options.app || { globalData: {} },
    Page(value) { definition = value },
    Component() {},
  }
  const requirePage = name => mocks[name] || defaults[name] || {}
  const wrapper = vm.runInNewContext(`(function(require, module, exports) {\n${source}\n})`, { console, Promise, wx, setTimeout: sandbox.setTimeout, clearTimeout: sandbox.clearTimeout, getCurrentPages: sandbox.getCurrentPages, getApp: sandbox.getApp, Page: sandbox.Page }, { filename: relative })
  const module = { exports: {} }
  wrapper(requirePage, module, module.exports)
  const page = definition
  const bound = { ...page, data: JSON.parse(JSON.stringify(page.data || {})), setData(updates, callback) { Object.assign(this.data, updates); if (callback) callback() }, __timers: timers, __runTimers() { for (const [id, item] of [...timers]) { timers.delete(id); item.callback() } } }
  for (const [key, value] of Object.entries(page)) if (typeof value === 'function') bound[key] = value.bind(bound)
  return bound
}

test('订单和处方余额读取失败时不会误报余额不足或发起付款', async () => {
  for (const file of ['pages/order/detail.js', 'pages/prescription/detail.js']) {
    let payments = 0
    const page = loadPage(file, {
      '../../utils/auth': { isLoggedIn: () => true, fetchMe: async () => { throw new Error('timeout') } },
      '../../utils/api': { payAppointment: async () => { payments++ }, payPrescription: async () => { payments++ } },
    })
    if (file.includes('order')) page.data.order = { id: 1, fee: 20, canPay: true }
    else page.data.prescription = { id: 2, totalAmount: 20, status: 'unpaid' }
    page.data.payMethod = 'balance'
    await page.loadBalance()
    await page.doPay()
    assert.equal(payments, 0, file)
  }
})

test('订单和处方详情只接受最新响应', async () => {
  for (const file of ['pages/order/detail.js', 'pages/prescription/detail.js']) {
    const first = deferred(), second = deferred()
    let calls = 0
    const api = file.includes('order')
      ? { getAppointmentById: () => (++calls === 1 ? first.promise : second.promise) }
      : { getPrescription: () => (++calls === 1 ? first.promise : second.promise) }
    const page = loadPage(file, { '../../utils/api': api })
    if (file.includes('order')) { page.loadOrder(1); page.loadOrder(1) }
    else { page.prescriptionId = 2; page.loadData(); page.loadData() }
    second.resolve(file.includes('order') ? { id: 1, status: 'pending', amount: 20 } : { id: 2, status: 'paid', totalAmount: 20, items: [] })
    await flush(); first.resolve(file.includes('order') ? { id: 1, status: 'unpaid', amount: 20 } : { id: 2, status: 'unpaid', totalAmount: 20, items: [] }); await flush()
    assert.equal(file.includes('order') ? page.data.order.status : page.data.prescription.status, file.includes('order') ? 'pending' : 'paid')
  }
})

test('支付成功离页后延时跳转不会继续执行', async () => {
  const page = loadPage('pages/prescription/detail.js', {
    '../../utils/auth': { isLoggedIn: () => true, fetchMe: async () => ({ balance: 100 }) },
    '../../utils/api': { payPrescription: async () => {}, getPrescription: async () => ({ id: 2, status: 'paid', totalAmount: 20, items: [] }) },
  })
  page.data.prescription = { id: 2, status: 'unpaid', totalAmount: 20 }; page.data.payMethod = 'alipay';
  await page.doPay(); page.onUnload(); page.__runTimers(); assert.equal(page.__timers.size, 0)
})

test('无1号科室时使用首个有效科室，非法分诊参数保留原文', async () => {
  let requestedDept = null
  const schedule = loadPage('pages/schedule/list.js', {
    '../../utils/api': { getDepartments: async () => [{ id: 2, name: '合成科室' }] },
  })
  schedule.loadDoctors = () => { requestedDept = schedule.data.activeDeptId }
  await schedule.loadDepartments(); await flush(); assert.equal(requestedDept, 2)
  let analyzed = ''
  const triage = loadPage('pages/triage/result.js', {
    '../../utils/api': { triageAnalyze: async value => { analyzed = value; return { departments: [] } } },
  })
  triage.onLoad({ symptom: '100%' }); await flush(); assert.equal(analyzed, '100%')
})

test('隐私授权不注册不完整的自定义回调', () => {
  const state = { registered: false }
  const source = fs.readFileSync(path.join(root, 'wechat-miniprogram/miniprogram/utils/privacy.js'), 'utf8')
  const module = { exports: {} }
  vm.runInNewContext(`(function(module,exports,wx){${source}\n})(module,module.exports,{onNeedPrivacyAuthorization(){state.registered=true}})`, { module, state })
  assert.equal(typeof module.exports.setupPrivacyAuthorization, 'function')
  assert.equal(state.registered, false)
})
