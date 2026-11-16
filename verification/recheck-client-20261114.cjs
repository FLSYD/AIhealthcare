// 前端定向复查探针（2026-11-14）：只读取真实源码，用桩替代 wx 与后端，不访问真实后端/支付。
// 运行：node verification/recheck-client-20261114.cjs
// 输出：.runtime/full-review-20261114-01/client-review-probes.json 与 client-review-probes.log
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const { pathToFileURL } = require('node:url')

const root = path.resolve(__dirname, '..')
const outDir = path.join(root, '.runtime', 'full-review-20261114-01')
fs.mkdirSync(outDir, { recursive: true })
const results = []
const logLines = []
function log(line) { logLines.push(line); console.log(line) }

// 每个探针返回 { observed, expected, reproduced }；reproduced=true 表示复现了缺陷行为，false 表示行为正常。
async function probe(id, title, fn) {
  try {
    const r = await fn()
    results.push({ id, title, ...r })
    log(`[${r.reproduced ? '复现' : '正常'}] ${id} ${title}\n    观测: ${JSON.stringify(r.observed)}\n    期望: ${r.expected}`)
  } catch (error) {
    results.push({ id, title, error: String(error && error.stack || error) })
    log(`[探针错误] ${id} ${title}: ${error && error.message}`)
  }
}
const settle = async () => { for (let i = 0; i < 6; i++) await new Promise((resolve) => setImmediate(resolve)) }
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))

// ---------------- 小程序：沙箱加载器（真实源码 + wx 桩） ----------------
function createWx(handler) {
  const store = new Map()
  const wx = {
    store, toasts: [], navs: [], requests: [],
    getStorageSync: (key) => (store.has(key) ? store.get(key) : ''),
    setStorageSync: (key, value) => { store.set(key, value) },
    removeStorageSync: (key) => { store.delete(key) },
    showToast(o) { wx.toasts.push(o.title) },
    showLoading() {}, hideLoading() {}, hideToast() {},
    navigateTo(o) { wx.navs.push(['navigateTo', o.url]) },
    redirectTo(o) { wx.navs.push(['redirectTo', o.url]) },
    switchTab(o) { wx.navs.push(['switchTab', o.url]) },
    navigateBack() { wx.navs.push(['navigateBack']) },
    showModal() {},
    request(o) {
      wx.requests.push({ url: o.url, method: o.method || 'GET', data: o.data })
      Promise.resolve(handler(o, wx)).then((res) => {
        if (res === 'HOLD') return
        if (res && res.fail) o.fail({ errMsg: res.fail })
        else o.success(res)
      })
    }
  }
  return wx
}
const ok = (data) => ({ statusCode: 200, data: { code: 200, data } })

function createLoader(wx, mocks = {}, globalsExtra = {}) {
  const cache = new Map()
  const app = { globalData: { token: '', user: null } }
  function load(abs) {
    if (cache.has(abs)) return cache.get(abs).exports
    const module = { exports: {} }
    cache.set(abs, module)
    const source = fs.readFileSync(abs, 'utf8')
    const sandbox = {
      console, Promise, Date, Math, JSON, Number, String, Array, Object, Error, RegExp, Set, Map,
      setTimeout: (cb, delay) => setTimeout(cb, Math.min(delay || 0, 5)),
      clearTimeout, wx, module, exports: module.exports,
      getApp: () => app, getCurrentPages: () => [{}, {}],
      require: (spec) => {
        const base = path.basename(spec)
        if (mocks[base]) return mocks[base]
        let target = path.resolve(path.dirname(abs), spec)
        if (!target.endsWith('.js')) target += '.js'
        if (path.basename(target) === 'config.js') return { baseUrl: 'http://stub.invalid' }
        return load(target)
      },
      ...globalsExtra
    }
    vm.runInNewContext(`(function(){\n${source}\n})()`, sandbox, { filename: abs })
    return module.exports
  }
  return { load, app }
}
const mp = (rel) => path.join(root, 'wechat-miniprogram', 'miniprogram', rel)

function loadPageDef(rel, wx, mocks) {
  let def
  const { load, app } = createLoader(wx, mocks, { Page: (value) => { def = value } })
  load(mp(rel))
  const instance = {
    data: { ...def.data },
    setData(updates, cb) { Object.assign(this.data, updates); if (cb) cb() }
  }
  for (const [key, value] of Object.entries(def)) if (key !== 'data' && typeof value === 'function') instance[key] = value.bind(instance)
  return { instance, load, app }
}

const NOOP_MOCKS = { tab: { setTabBar() {} }, privacy: { setupPrivacyAuthorization() {} } }

// ================= 小程序探针 =================
async function main() {
  // --- F08：启动时 fetchMe 失败是否清除登录态（真实 app.js + auth.js + request.js） ---
  for (const [name, res, expectCleared] of [
    ['超时(wx.request fail)', { fail: 'request:fail timeout' }, false],
    ['后端不可达(5xx 非 JSON)', { statusCode: 502, data: '<html>' }, false],
    ['明确 401', { statusCode: 401, data: { code: 401, message: '登录已过期' } }, true]
  ]) {
    await probe(`MP-F08-${name}`, `启动 fetchMe 遇到 ${name} 后登录态`, async () => {
      const wx = createWx(() => res)
      wx.store.set('token', 'TOKEN_STUB'); wx.store.set('user', { id: 1 })
      let def
      const { load } = createLoader(wx, NOOP_MOCKS, { App: (v) => { def = v } })
      load(mp('app.js'))
      const instance = { globalData: { ...def.globalData } }
      def.onLaunch.call(instance)
      await settle()
      const cleared = !wx.store.has('token')
      return { observed: { tokenCleared: cleared }, expected: expectCleared ? '401 应清除' : '网络类错误不应清除登录态', reproduced: expectCleared ? false : cleared }
    })
  }

  // --- F06：排班日期切换竞态（真实 schedule/detail.js，adapter 用桩把 id 映射为可观测值） ---
  const adapterStub = {
    enrichDoctor: (d) => d,
    groupSchedules: (list) => ({ morning: list.map((s) => ({ time: s.timeSlot, scheduleId: s.id, available: true, date: s.scheduleDate })), afternoon: [], hasAnySchedule: list.length > 0, hasAvailable: list.length > 0 })
  }
  await probe('MP-F06-乱序响应', '连续切换 D1→D2，D1 响应晚到覆盖 D2 的号源', async () => {
    const held = []
    const wx = createWx((o) => { if (o.url.includes('/api/schedules')) { held.push(o); return 'HOLD' } return ok(null) })
    const { instance } = loadPageDef('pages/schedule/detail.js', wx, { ...NOOP_MOCKS, adapter: adapterStub })
    instance.setData({ doctorId: 1, doctor: { id: 1 } })
    instance.onDateChange({ detail: { value: '2026-11-15' } })
    instance.onDateChange({ detail: { value: '2026-11-16' } })
    const reply = (o, rows) => o.success(ok(rows))
    reply(held[1], [{ id: 22, timeSlot: '09:00', scheduleDate: '2026-11-16' }])
    await settle()
    reply(held[0], [{ id: 11, timeSlot: '09:00', scheduleDate: '2026-11-15' }])
    await settle()
    const shown = instance.data.morning.map((s) => ({ scheduleId: s.scheduleId, date: s.date }))
    instance.onSlotSelect({ detail: { time: '09:00', scheduleId: instance.data.morning[0].scheduleId } })
    instance.data.doctor = { id: 1 }
    instance.onBook()
    return { observed: { selectedDate: instance.data.selectedDate, shown, nav: wx.navs.at(-1)[1] }, expected: '选中日期为 D2 时只展示 D2 的号源', reproduced: shown.some((s) => s.date !== instance.data.selectedDate) }
  })
  await probe('MP-F06-旧选择保留', '已选时段后切换日期，响应返回前立即预约', async () => {
    const held = []
    const wx = createWx((o) => { if (o.url.includes('/api/schedules')) { held.push(o); return 'HOLD' } return ok(null) })
    const { instance } = loadPageDef('pages/schedule/detail.js', wx, { ...NOOP_MOCKS, adapter: adapterStub })
    instance.setData({ doctorId: 1, doctor: { id: 1 }, selectedDate: '2026-11-14' })
    instance.onSlotSelect({ detail: { time: '09:00', scheduleId: 5 } })
    instance.onDateChange({ detail: { value: '2026-11-15' } })
    instance.onBook()
    return { observed: { nav: wx.navs.at(-1) && wx.navs.at(-1)[1] }, expected: '切换日期后旧时段选择应被清除，不能带旧 scheduleId 进入确认页', reproduced: !!wx.navs.length && /scheduleDate=2026-11-15/.test(wx.navs.at(-1)[1]) && /scheduleId=5/.test(wx.navs.at(-1)[1]) }
  })

  // --- F07：各支付路径成功后 fetchMe 失败 ---
  const payHandler = (nativeEnabled, extra = {}) => (o) => {
    if (o.url.includes('/native/config')) return ok({ enabled: nativeEnabled, mode: nativeEnabled ? 'NATIVE' : 'MOCK' })
    if (o.url.includes('/api/auth/me')) return { fail: 'request:fail timeout' }
    if (o.url.endsWith('/api/payments/wechat/native') && o.method === 'POST') return ok({ paymentId: 77, status: 'SUCCEEDED' })
    if (o.url.endsWith('/api/appointments') && o.method === 'POST') return ok({ id: 9 })
    if (/\/pay$/.test(o.url) || o.url.includes('/api/wallet/recharge')) return ok(null)
    if (extra.default) return extra.default(o)
    return ok(null)
  }
  const confirmData = { scheduleId: 1, patientName: '测试患者', patientPhone: '13800000000', feeAmount: '20.00' }
  for (const [label, method, native] of [['LEGACY-微信', 'wechat', false], ['LEGACY-支付宝模拟', 'alipay', false], ['NATIVE-微信', 'wechat', true]]) {
    await probe(`MP-F07-confirm-${label}`, `确认预约(${label})支付后刷新余额失败`, async () => {
      const wx = createWx(payHandler(native))
      wx.store.set('token', 'T'); wx.store.set('user', { id: 1 })
      const { instance } = loadPageDef('pages/appointment/confirm.js', wx, NOOP_MOCKS)
      instance.setData({ ...confirmData, payMethod: method })
      await instance.doSubmit()
      await sleep(30)
      const redirected = wx.navs.some((n) => n[0] === 'redirectTo' && n[1].includes('order/detail?id=9'))
      const success = wx.toasts.includes('支付成功')
      return { observed: { success, redirected, toasts: wx.toasts }, expected: '显示支付成功并跳订单详情', reproduced: !(success && redirected) }
    })
  }
  for (const [label, method, native] of [['LEGACY', 'wechat', false], ['NATIVE', 'wechat', true]]) {
    await probe(`MP-F07-order-${label}`, `订单详情支付(${label})后刷新余额失败`, async () => {
      const wx = createWx(payHandler(native))
      wx.store.set('token', 'T'); wx.store.set('user', { id: 1 })
      const { instance } = loadPageDef('pages/order/detail.js', wx, NOOP_MOCKS)
      instance.setData({ order: { id: '9', canPay: true, fee: 20 }, payMethod: method })
      await instance.doPay()
      await sleep(30)
      const reloaded = wx.requests.some((r) => r.url.endsWith('/api/appointments/9') && r.method === 'GET')
      return { observed: { success: wx.toasts.includes('支付成功'), reloaded, toasts: wx.toasts }, expected: '提示成功并重新加载订单', reproduced: !(wx.toasts.includes('支付成功') && reloaded) }
    })
    await probe(`MP-F07-prescription-${label}`, `处方支付(${label})后刷新余额失败`, async () => {
      const wx = createWx(payHandler(native))
      wx.store.set('token', 'T'); wx.store.set('user', { id: 1 })
      const { instance } = loadPageDef('pages/prescription/detail.js', wx, NOOP_MOCKS)
      instance.setData({ prescription: { id: 3, totalAmount: 10 }, payMethod: method })
      await instance.doPay()
      await sleep(30)
      const ok1 = wx.toasts.some((t) => /支付成功/.test(t))
      return { observed: { success: ok1, toasts: wx.toasts }, expected: '提示支付成功', reproduced: !ok1 }
    })
  }
  await probe('MP-F07-recharge-LEGACY', '充值(LEGACY)成功后刷新余额失败', async () => {
    const wx = createWx(payHandler(false))
    wx.store.set('token', 'T'); wx.store.set('user', { id: 1 })
    const { instance } = loadPageDef('pages/recharge/recharge.js', wx, NOOP_MOCKS)
    instance.setData({ rechargeAmount: '50.00', payMethod: 'wechat' })
    await instance.doRecharge()
    await sleep(30)
    const failedToast = wx.toasts.some((t) => t === '充值失败')
    return { observed: { toasts: wx.toasts }, expected: '提示充值成功(附余额刷新失败)，不得提示充值失败', reproduced: failedToast || !wx.toasts.some((t) => /充值成功/.test(t)) }
  })
  await probe('MP-余额未知', '余额接口失败时余额支付被误判为余额不足', async () => {
    const wx = createWx(payHandler(false))
    wx.store.set('token', 'T'); wx.store.set('user', { id: 1, balance: 500 })
    const { instance } = loadPageDef('pages/appointment/confirm.js', wx, NOOP_MOCKS)
    instance.setData({ ...confirmData, payMethod: 'balance' })
    await instance.doSubmit()
    const rejected = wx.toasts.includes('余额不足，请先充值')
    return { observed: { toasts: wx.toasts, createCalls: wx.requests.filter((r) => r.method === 'POST').length }, expected: '余额未获取成功时应提示重试/刷新余额，而非“余额不足”(账户实际余额 500，仅作说明)', reproduced: rejected }
  })

  // --- F12：分页/空病历/充值历史 ---
  await probe('MP-F12-订单列表', '订单列表总数 120 时只请求首页 50 条且无继续加载', async () => {
    const records = Array.from({ length: 50 }, (_, i) => ({ id: 1000 - i, status: 'completed', scheduleDate: '2026-11-01', timeSlot: '09:00' }))
    const wx = createWx((o) => (o.url.includes('/api/appointments') ? ok({ records, total: 120 }) : ok(null)))
    wx.store.set('token', 'T')
    const { instance } = loadPageDef('pages/order/list.js', wx, NOOP_MOCKS)
    instance.loadOrders(0)
    await settle()
    const reqs = wx.requests.filter((r) => r.url.includes('/api/appointments'))
    const hasMore = typeof instance.onReachBottom === 'function' || typeof instance.loadMore === 'function'
    return { observed: { requestCount: reqs.length, params: reqs[0] && reqs[0].data, shown: instance.data.orders.length, serverTotal: 120, hasLoadMore: hasMore }, expected: '总数超过一页时旧订单可继续加载', reproduced: !hasMore && instance.data.orders.length < 120 }
  })
  await probe('MP-F12-空病历', '病历接口返回 null 时页面一直处于 loading', async () => {
    const wx = createWx((o) => (o.url.includes('/consultation') ? ok(null) : ok(null)))
    wx.store.set('token', 'T')
    const { instance } = loadPageDef('pages/visit/record.js', wx, NOOP_MOCKS)
    instance.onLoad({ id: '9' })
    await settle()
    return { observed: { loading: instance.data.loading, toasts: wx.toasts }, expected: '无病历时结束 loading 并展示空态', reproduced: instance.data.loading === true }
  })
  await probe('MP-F12-充值历史', '充值历史应请求 type=recharge 由服务端分页过滤，不再在客户端截取后过滤', async () => {
    const records = Array.from({ length: 10 }, (_, i) => ({ id: i, type: 'recharge', typeText: '充值', amount: 10, createTime: '2026-11-13T10:00:00' }))
    const wx = createWx((o) => (o.url.includes('/api/wallet/logs') ? ok({ records, total: 11, current: 1, size: 10 }) : ok({ balance: 0 })))
    wx.store.set('token', 'T'); wx.store.set('user', { id: 1 })
    const { instance } = loadPageDef('pages/recharge/recharge.js', wx, NOOP_MOCKS)
    await instance.loadLogs()
    const req = wx.requests.find((r) => r.url.includes('/api/wallet/logs'))
    const missingType = !(req && req.data && req.data.type === 'recharge')
    return { observed: { params: req && req.data, rechargeLogsShown: instance.data.logs.length, hasMore: instance.data.logsHasMore }, expected: '请求携带 type=recharge，且可加载更多', reproduced: missingType }
  })
  // ================= admin-web 探针 =================
  const tmp = path.join(outDir, 'tmp-admin-probe')
  fs.mkdirSync(tmp, { recursive: true })
  const adminSrc = (name) => fs.readFileSync(path.join(root, 'admin-web', 'src', 'lib', name), 'utf8')
  const adminPkg = pathToFileURL(path.join(root, 'admin-web', 'package.json')).href
  fs.writeFileSync(path.join(tmp, 'vue-stub.mjs'), `import { createRequire } from 'node:module'
const vue = createRequire(${JSON.stringify(adminPkg)})('vue')
export const reactive = vue.reactive, ref = vue.ref
export const onMounted = cb => globalThis.__mounted.push(cb)
export const onBeforeUnmount = cb => globalThis.__unmount.push(cb)
`)
  const loadAllSrc = adminSrc('api.js').slice(adminSrc('api.js').indexOf('export async function loadAll'))
  fs.writeFileSync(path.join(tmp, 'api.mjs'), `export const api = { get: (...args) => globalThis.__apiGet(...args) }\n${loadAllSrc}\n`)
  fs.writeFileSync(path.join(tmp, 'format.mjs'), adminSrc('format.js'))
  for (const name of ['usePagedList', 'useAppointmentList', 'useStatusRefresh']) {
    fs.writeFileSync(path.join(tmp, `${name}.mjs`), adminSrc(`${name}.js`).replace(/from 'vue'/g, "from './vue-stub.mjs'").replace(/from '\.\/(api|format|usePagedList)'/g, "from './$1.mjs'"))
  }
  globalThis.__mounted = []; globalThis.__unmount = []
  const mod = (n) => import(pathToFileURL(path.join(tmp, `${n}.mjs`)).href)
  const { usePagedList } = await mod('usePagedList')
  const { useAppointmentList } = await mod('useAppointmentList')
  const { useStatusRefresh } = await mod('useStatusRefresh')
  const calls = []
  globalThis.__apiGet = async (url, opts) => { calls.push({ url, params: { ...(opts && opts.params) }, quiet: opts && opts.quiet }); return globalThis.__apiResult(url, opts) }

  await probe('ADM-分页-后台刷新用未提交筛选', '输入关键字但未点查询时，15 秒后台刷新直接使用草稿筛选', async () => {
    calls.length = 0
    globalThis.__apiResult = async () => ({ list: [{ id: 1 }], total: 1 })
    const paged = usePagedList('/doctor-workspace/appointments', { keyword: '', status: '' })
    await paged.load()
    paged.filters.keyword = '草稿关键字'
    await paged.load({ background: true })
    return { observed: { lastParams: calls.at(-1).params }, expected: '后台刷新应沿用上次已提交的查询条件', reproduced: calls.at(-1).params.keyword === '草稿关键字' }
  })
  await probe('ADM-分页-越界回退', '当前页被删空后回退到最后一页并重新加载', async () => {
    calls.length = 0
    globalThis.__apiResult = async (_u, opts) => (opts.params.pageNum === 3 ? { list: [], total: 15 } : { list: [{ id: 11 }], total: 15 })
    const paged = usePagedList('/x', {})
    paged.page.value = 3
    await paged.load()
    return { observed: { page: paged.page.value, rows: paged.rows.value.length, requests: calls.map((c) => c.params.pageNum) }, expected: '回到第 2 页并有数据', reproduced: !(paged.page.value === 2 && paged.rows.value.length === 1) }
  })
  await probe('ADM-分页-乱序响应', '较早发出的前台请求晚到不得覆盖较新结果', async () => {
    const waiting = []
    globalThis.__apiResult = (_u, opts) => new Promise((resolve) => waiting.push({ resolve, keyword: opts.params.keyword }))
    const paged = usePagedList('/x', { keyword: '' })
    paged.filters.keyword = 'A'; const first = paged.load()
    paged.filters.keyword = 'B'; const second = paged.load()
    waiting[1].resolve({ list: [{ id: 'B' }], total: 1 })
    await second
    waiting[0].resolve({ list: [{ id: 'A' }], total: 1 })
    await first
    return { observed: { rows: paged.rows.value, loading: paged.loading.value }, expected: '保留 B 的结果', reproduced: paged.rows.value[0].id !== 'B' }
  })
  await probe('ADM-分页-后台失败保留旧数据', '后台刷新失败保留上次列表并提示，下一次成功后清除提示', async () => {
    let fail = false
    globalThis.__apiResult = async () => { if (fail) throw new Error('断网'); return { list: [{ id: 1 }], total: 1 } }
    const paged = usePagedList('/x', {})
    await paged.load()
    fail = true; await paged.load({ background: true })
    const afterFail = { rows: paged.rows.value.length, error: paged.error.value }
    fail = false; await paged.load({ background: true })
    return { observed: { afterFail, errorAfterRecover: paged.error.value }, expected: '失败时保留 1 行并提示；恢复后提示清空', reproduced: !(afterFail.rows === 1 && afterFail.error && paged.error.value === '') }
  })
  await probe('ADM-预约列表-待缴药费/待发药合并', '待缴药费与待发药按处方状态拆分、倒序、分页', async () => {
    const rowsRx = [
      { id: 1, status: 'pending_rx_pay', prescriptionStatus: 'unpaid', scheduleDate: '2026-11-10', timeSlot: '09:00' },
      { id: 2, status: 'pending_rx_pay', prescriptionStatus: 'paid', scheduleDate: '2026-11-12', timeSlot: '09:00' },
      { id: 3, status: 'pending_rx_pay', prescriptionStatus: 'unpaid', scheduleDate: '2026-11-13', timeSlot: '14:00' },
      { id: 4, status: 'pending_rx_pay', prescriptionStatus: 'unpaid', scheduleDate: '2026-11-13', timeSlot: '09:00' }
    ]
    globalThis.__apiResult = async (_u, opts) => (opts.params.status === 'pending_rx_pay' ? { list: rowsRx, total: rowsRx.length } : { list: [], total: 0 })
    const rx = useAppointmentList('/appointment/page', { status: 'pending_rx_pay' }); await rx.load()
    const pickup = useAppointmentList('/appointment/page', { status: 'pending_pickup' }); await pickup.load()
    const ids = (p) => p.rows.value.map((r) => r.id)
    return { observed: { pending_rx_pay: ids(rx), pending_pickup: ids(pickup), totals: [rx.total.value, pickup.total.value] }, expected: '待缴药费 [3,4,1]，待发药 [2]', reproduced: JSON.stringify([ids(rx), ids(pickup)]) !== JSON.stringify([[3, 4, 1], [2]]) }
  })
  await probe('ADM-预约列表-合并查询期间状态推进', '两次查询之间处方被缴费，同一预约不得同时出现在两个页签', async () => {
    const unpaidView = { id: 7, status: 'pending_rx_pay', prescriptionStatus: 'unpaid', scheduleDate: '2026-11-13', timeSlot: '09:00', updateTime: '2026-11-13 10:00:00' }
    const paidView = { ...unpaidView, status: 'pending_pickup', prescriptionStatus: 'paid', updateTime: '2026-11-13 10:00:05' }
    globalThis.__apiResult = async (_u, opts) => (opts.params.status === 'pending_rx_pay' ? { list: [unpaidView], total: 1 } : { list: [paidView], total: 1 })
    const rx = useAppointmentList('/appointment/page', { status: 'pending_rx_pay' }); await rx.load()
    const pickup = useAppointmentList('/appointment/page', { status: 'pending_pickup' }); await pickup.load()
    return { observed: { rx: rx.rows.value.map((r) => r.id), pickup: pickup.rows.value.map((r) => r.id) }, expected: '只出现在待发药', reproduced: rx.rows.value.length > 0 && pickup.rows.value.length > 0 }
  })
  await probe('ADM-预约列表-普通状态直通', '非处方状态仍走服务端分页，不全量拉取', async () => {
    calls.length = 0
    globalThis.__apiResult = async () => ({ list: [], total: 0 })
    const list = useAppointmentList('/appointment/page', { status: 'completed' }); await list.load()
    return { observed: { calls: calls.length, params: calls[0].params }, expected: '1 次请求，带 pageNum/pageSize', reproduced: !(calls.length === 1 && calls[0].params.pageSize === 10) }
  })
  await probe('ADM-useStatusRefresh', '刷新进行中不重入、失败不抛出、卸载后停止', async () => {
    const timers = []
    globalThis.window = { setInterval: (cb) => { timers.push(cb); return 1 }, clearInterval() {}, addEventListener() {}, removeEventListener() {} }
    globalThis.document = { hidden: false, addEventListener() {}, removeEventListener() {} }
    globalThis.__mounted = []; globalThis.__unmount = []
    let runs = 0, release
    useStatusRefresh(() => { runs++; return new Promise((_r, reject) => { release = reject }) }, {})
    globalThis.__mounted.forEach((cb) => cb())
    timers[0](); timers[0]()
    const concurrent = runs
    release(new Error('x')); await settle()
    timers[0](); await settle(); const afterFail = runs
    globalThis.__unmount.forEach((cb) => cb()); timers[0](); await settle()
    return { observed: { concurrent, afterFail, afterUnmount: runs }, expected: '1,2,2', reproduced: !(concurrent === 1 && afterFail === 2 && runs === 2) }
  })

  fs.rmSync(tmp, { recursive: true, force: true })
  fs.writeFileSync(path.join(outDir, 'client-review-probes.json'), JSON.stringify(results, null, 2))
  fs.writeFileSync(path.join(outDir, 'client-review-probes.log'), logLines.join('\n') + '\n')
  const errors = results.filter((r) => r.error).length
  log(`\n合计 ${results.length} 项，复现 ${results.filter((r) => r.reproduced).length}，正常 ${results.filter((r) => r.reproduced === false).length}，探针错误 ${errors}`)
  process.exitCode = errors ? 1 : 0
}
main().catch((error) => { console.error(error); process.exitCode = 2 })
