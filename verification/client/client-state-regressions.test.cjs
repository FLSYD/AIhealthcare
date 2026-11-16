const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const assert = require('node:assert/strict')
const { test } = require('node:test')

const projectRoot = path.resolve(__dirname, '..', '..')

function deferred() {
  let resolve
  let reject
  const promise = new Promise((resolvePromise, rejectPromise) => {
    resolve = resolvePromise
    reject = rejectPromise
  })
  return { promise, resolve, reject }
}

async function settle() {
  await new Promise((resolve) => setImmediate(resolve))
  await new Promise((resolve) => setImmediate(resolve))
}

function runAllTimers(timers) {
  let executed = 0
  while (timers.length) {
    const pending = timers.splice(0)
    for (const timer of pending) {
      timer.callback()
      executed += 1
    }
  }
  return executed
}

// 支付工具默认按 Native 关闭（MOCK）处理，页面测试只需覆盖自己关心的导出
function withDefaultPay(specifier, mod) {
  if (specifier !== '../../utils/pay') return mod
  return {
    resolvePayMode: () => Promise.resolve('LEGACY'),
    payNative: () => Promise.reject(new Error('测试默认不应走 Native')),
    loadPayMode: () => Promise.resolve('MOCK'),
    decoratePayMethods: (methods) => methods,
    payTipFor: () => '',
    ...mod
  }
}

function loadCommonJs(relativePath, requireMock, options = {}) {
  const filename = path.join(projectRoot, relativePath)
  const source = fs.readFileSync(filename, 'utf8')
  const module = { exports: {} }
  const sandbox = {
    console,
    Promise,
    setTimeout: options.setTimeout || setTimeout,
    clearTimeout,
    wx: options.wx || {},
    module,
    exports: module.exports
  }
  const wrapper = vm.runInNewContext(
    `(function(require, module, exports) {\n${source}\n})`,
    sandbox,
    { filename }
  )
  wrapper(requireMock, module, module.exports)
  return module.exports
}

function loadPage(relativePath, requireMock, options = {}) {
  const filename = path.join(projectRoot, relativePath)
  const source = fs.readFileSync(filename, 'utf8')
  let definition
  const timers = []
  const wx = options.wx || {}
  const sandbox = {
    console,
    Promise,
    setTimeout(callback, delay) {
      timers.push({ callback, delay })
      return timers.length
    },
    clearTimeout() {},
    wx,
    require: (specifier) => withDefaultPay(specifier, requireMock(specifier)),
    getApp() {
      return options.app || null
    },
    getCurrentPages() {
      return options.pages || []
    },
    Page(value) {
      definition = value
    }
  }
  vm.runInNewContext(source, sandbox, { filename })
  if (!definition) throw new Error(`Page 未注册: ${relativePath}`)
  return { definition, timers, wx }
}

function loadApp(requireMock, options = {}) {
  const filename = path.join(projectRoot, 'wechat-miniprogram/miniprogram/app.js')
  const source = fs.readFileSync(filename, 'utf8')
  let definition
  const sandbox = {
    console,
    Promise,
    setTimeout,
    clearTimeout,
    wx: options.wx || {},
    require: requireMock,
    App(value) {
      definition = value
    }
  }
  vm.runInNewContext(source, sandbox, { filename })
  if (!definition) throw new Error('App 未注册: wechat-miniprogram/miniprogram/app.js')
  return definition
}

function instantiatePage(definition, dataOverrides = {}) {
  const instance = {
    data: { ...(definition.data || {}), ...dataOverrides },
    setData(updates, callback) {
      Object.assign(this.data, updates)
      if (callback) callback()
    }
  }
  for (const [key, value] of Object.entries(definition)) {
    if (key !== 'data' && typeof value === 'function') instance[key] = value.bind(instance)
  }
  return instance
}

function createToast() {
  const messages = []
  const toast = (message) => {
    messages.push(String(message))
    return message
  }
  toast.success = (message) => {
    messages.push(String(message))
    return message
  }
  toast.messages = messages
  return toast
}

function createRequestBoundary() {
  const requests = []
  return {
    requests,
    requireMock(specifier) {
      if (specifier === './native-pay') {
        return {
          getConfig: () => Promise.resolve({ enabled: false, mode: 'MOCK' }),
          pay: () => Promise.reject(new Error('测试默认不应走 Native'))
        }
      }
      if (specifier !== './request') throw new Error(`unexpected wallet dependency: ${specifier}`)
      return {
        request(options) {
          requests.push(options)
          return Promise.resolve({ ok: true })
        }
      }
    }
  }
}

function createWalletStorage() {
  const storage = { user: { id: 11 } }
  return {
    getStorageSync(key) { return storage[key] },
    setStorageSync(key, value) { storage[key] = JSON.parse(JSON.stringify(value)) },
    removeStorageSync(key) { delete storage[key] }
  }
}

function formatMocks() {
  return {
    buildDateStrip: () => [],
    formatDateTime: (date, time) => `${date || ''} ${time || ''}`.trim(),
    formatFee: (value) => Number(value || 0).toFixed(2),
    formatServerDateTime: (value) => String(value || '')
  }
}

test('F06修复：排班 A/B 倒序响应时只展示并提交 B 日期的 scheduleId', async () => {
  const requests = []
  const api = {
    getSchedules(params) {
      const pending = deferred()
      requests.push({ params: { ...params }, ...pending })
      return pending.promise
    }
  }
  const adapter = {
    enrichDoctor: (value) => value,
    groupSchedules(schedules) {
      const items = (schedules || []).map((schedule) => ({
        time: schedule.timeSlot,
        available: true,
        scheduleId: schedule.id,
        remainQuota: schedule.remainQuota
      }))
      return {
        morning: items,
        afternoon: [],
        hasAnySchedule: items.length > 0,
        hasAvailable: items.length > 0
      }
    }
  }
  const { definition } = loadPage(
    'wechat-miniprogram/miniprogram/pages/schedule/detail.js',
    (specifier) => {
      if (specifier === '../../utils/api') return api
      if (specifier === '../../utils/adapter') return adapter
      if (specifier === '../../utils/format') return formatMocks()
      if (specifier === '../../utils/toast') return createToast()
      throw new Error(`unexpected schedule dependency: ${specifier}`)
    }
  )
  const page = instantiatePage(definition, {
    doctorId: 7,
    selectedDate: '2026-11-20',
    selectedTime: '',
    selectedScheduleId: 0
  })

  page.loadSlots()
  page.setData({ selectedDate: '2026-11-21' })
  page.loadSlots()

  const requestA = requests.find((item) => item.params.scheduleDate === '2026-11-20')
  const requestB = requests.find((item) => item.params.scheduleDate === '2026-11-21')
  assert.ok(requestA && requestB, '应捕获 A/B 两次排班请求')

  requestB.resolve([{ id: 202, timeSlot: '09:00', status: 1, remainQuota: 1 }])
  await settle()
  requestA.resolve([{ id: 101, timeSlot: '09:00', status: 1, remainQuota: 1 }])
  await settle()

  const displayed = page.data.morning.find((slot) => slot.available)
  assert.ok(displayed, '应显示一个可预约时段')
  page.onSlotSelect({ detail: { time: displayed.time, scheduleId: displayed.scheduleId } })

  const correct = displayed.scheduleId === 202
    && page.data.selectedDate === '2026-11-21'
    && page.data.selectedScheduleId === 202
  assert.strictEqual(
    correct,
    true,
    `B 日期展示或提交了其他日期的号源: ${JSON.stringify({ displayed, data: page.data })}`
  )
})

test('F02修复：明确成功的充值携带幂等键，余额刷新失败不再报告充值失败', async () => {
  const boundary = createRequestBoundary()
  const walletApi = loadCommonJs(
    'wechat-miniprogram/miniprogram/utils/wallet-api.js',
    boundary.requireMock,
    { wx: createWalletStorage() }
  )
  const toast = createToast()
  let fetchMeCalls = 0
  let channelPayCalls = 0
  const { definition } = loadPage(
    'wechat-miniprogram/miniprogram/pages/recharge/recharge.js',
    (specifier) => {
      if (specifier === '../../utils/auth') {
        return {
          isLoggedIn: () => true,
          fetchMe() {
            fetchMeCalls += 1
            return Promise.reject(new Error('fetch-me-failed'))
          }
        }
      }
      if (specifier === '../../utils/wallet-api') return walletApi
      if (specifier === '../../utils/pay') {
        return {
          PAY_METHODS: [
            { id: 'wechat', name: '微信支付', desc: '模拟支付' },
            { id: 'alipay', name: '支付宝', desc: '模拟支付' }
          ],
          mockChannelPay() {
            channelPayCalls += 1
            return Promise.resolve()
          }
        }
      }
      if (specifier === '../../utils/toast') return toast
      if (specifier === '../../utils/format') return formatMocks()
      throw new Error(`unexpected recharge dependency: ${specifier}`)
    }
  )
  const page = instantiatePage(definition, {
    rechargeAmount: '100.00',
    payMethod: 'wechat',
    submitting: false
  })

  await page.doRecharge()
  await page.doRecharge()

  const hasIdempotencyKey = (options) => Boolean(
    options.header && options.header['Idempotency-Key']
  )
  const recharges = boundary.requests.filter((options) => options.url === '/api/wallet/recharge')
  assert.strictEqual(recharges.length, 2, '两次明确成功后的主动操作各产生一次请求')
  assert.ok(recharges.every(hasIdempotencyKey))
  assert.notStrictEqual(recharges[0].header['Idempotency-Key'], recharges[1].header['Idempotency-Key'])
  assert.strictEqual(fetchMeCalls, 2)
  assert.strictEqual(channelPayCalls, 2)
  assert.strictEqual(toast.messages.filter((message) => message === '充值成功').length, 2)
  assert.strictEqual(toast.messages.filter((message) => message.includes('余额刷新失败')).length, 2)
  assert.strictEqual(toast.messages.includes('fetch-me-failed'), false)
})

test('F07修复：预约支付成功但 fetchMe 失败仍显示支付成功并跳订单详情', async () => {
  const toast = createToast()
  let showToastCalls = 0
  let redirectCalls = 0
  const wx = {
    showToast: () => { showToastCalls += 1 },
    redirectTo: () => { redirectCalls += 1 }
  }
  let createCalls = 0
  let payCalls = 0
  let fetchMeCalls = 0
  const api = {
    getDoctorById: () => Promise.resolve({ id: 7, departmentName: '内科' }),
    createAppointment() {
      createCalls += 1
      return Promise.resolve({ id: 'appointment-1' })
    },
    payAppointment() {
      payCalls += 1
      return Promise.resolve()
    }
  }
  const loaded = loadPage(
    'wechat-miniprogram/miniprogram/pages/appointment/confirm.js',
    (specifier) => {
      if (specifier === '../../utils/auth') {
        return {
          isLoggedIn: () => true,
          fetchMe() {
            fetchMeCalls += 1
            return Promise.reject(new Error('balance-refresh-failed'))
          }
        }
      }
      if (specifier === '../../utils/toast') return toast
      if (specifier === '../../utils/api') return api
      if (specifier === '../../utils/adapter') return { enrichDoctor: (value) => value }
      if (specifier === '../../utils/format') return formatMocks()
      if (specifier === '../../utils/pay') {
        return {
          PAY_METHODS: [{ id: 'wechat', name: '微信支付', desc: '模拟支付' }],
          mockChannelPay: () => Promise.resolve(),
          needsPayDrawer: () => false
        }
      }
      throw new Error(`unexpected appointment dependency: ${specifier}`)
    },
    { wx }
  )
  const page = instantiatePage(loaded.definition, {
    scheduleId: 101,
    patientName: '测试患者',
    patientPhone: '13800000000',
    payMethod: 'wechat',
    feeAmount: '20.00',
    balance: '100.00',
    submitting: false
  })

  await page.doSubmit()
  const executedTimerCount = runAllTimers(loaded.timers)

  assert.strictEqual(createCalls, 1)
  assert.strictEqual(payCalls, 1)
  assert.strictEqual(fetchMeCalls, 1)
  assert.strictEqual(toast.messages.includes('balance-refresh-failed'), false)
  assert.strictEqual(showToastCalls, 1)
  assert.strictEqual(executedTimerCount, 1)
  assert.strictEqual(redirectCalls, 1)
})

test('F07修复：订单支付成功但 fetchMe 失败仍显示支付成功并刷新订单', async () => {
  const toast = createToast()
  let showToastCalls = 0
  let navigationCalls = 0
  const wx = {
    showToast: () => { showToastCalls += 1 },
    showModal() {},
    navigateTo: () => { navigationCalls += 1 },
    redirectTo: () => { navigationCalls += 1 },
    navigateBack: () => { navigationCalls += 1 }
  }
  let payCalls = 0
  let fetchMeCalls = 0
  let loadOrderCalls = 0
  const api = {
    payAppointment() {
      payCalls += 1
      return Promise.resolve()
    },
    getAppointmentById() {
      loadOrderCalls += 1
      return new Promise(() => {})
    }
  }
  const loaded = loadPage(
    'wechat-miniprogram/miniprogram/pages/order/detail.js',
    (specifier) => {
      if (specifier === '../../utils/api') return api
      if (specifier === '../../utils/auth') {
        return {
          isLoggedIn: () => true,
          fetchMe() {
            fetchMeCalls += 1
            return Promise.reject(new Error('balance-refresh-failed'))
          }
        }
      }
      if (specifier === '../../utils/adapter') return { toOrder: (value) => value }
      if (specifier === '../../utils/format') return formatMocks()
      if (specifier === '../../utils/pay') {
        return {
          PAY_METHODS: [{ id: 'wechat', name: '微信支付', desc: '模拟支付' }],
          mockChannelPay: () => Promise.resolve(),
          needsPayDrawer: () => false
        }
      }
      if (specifier === '../../utils/toast') return toast
      throw new Error(`unexpected order dependency: ${specifier}`)
    },
    { wx }
  )
  const page = instantiatePage(loaded.definition, {
    order: { id: 'order-1', canPay: true, fee: 20 },
    payMethod: 'wechat',
    balance: '100.00',
    paying: false
  })

  await page.doPay()
  const executedTimerCount = runAllTimers(loaded.timers)

  assert.strictEqual(payCalls, 1)
  assert.ok(fetchMeCalls >= 1)
  assert.strictEqual(loadOrderCalls, 1)
  assert.strictEqual(toast.messages.includes('balance-refresh-failed'), false)
  assert.strictEqual(showToastCalls, 1)
  assert.strictEqual(navigationCalls, 0)
  assert.strictEqual(executedTimerCount, 0)
})

test('F07修复：处方支付成功但 fetchMe 失败仍显示支付成功并返回', async () => {
  const toast = createToast()
  let showToastCalls = 0
  let navigateBackCalls = 0
  const wx = {
    showToast: () => { showToastCalls += 1 },
    navigateBack: () => { navigateBackCalls += 1 }
  }
  let payCalls = 0
  let fetchMeCalls = 0
  const api = {
    payPrescription() {
      payCalls += 1
      return Promise.resolve()
    },
    getPrescription: () => new Promise(() => {}),
    getPrescriptionByAppointment: () => new Promise(() => {})
  }
  const loaded = loadPage(
    'wechat-miniprogram/miniprogram/pages/prescription/detail.js',
    (specifier) => {
      if (specifier === '../../utils/api') return api
      if (specifier === '../../utils/auth') {
        return {
          isLoggedIn: () => true,
          fetchMe() {
            fetchMeCalls += 1
            return Promise.reject(new Error('balance-refresh-failed'))
          }
        }
      }
      if (specifier === '../../utils/format') return formatMocks()
      if (specifier === '../../utils/pay') {
        return {
          PAY_METHODS: [{ id: 'wechat', name: '微信支付', desc: '模拟支付' }],
          mockChannelPay: () => Promise.resolve(),
          needsPayDrawer: () => false
        }
      }
      if (specifier === '../../utils/toast') return toast
      throw new Error(`unexpected prescription dependency: ${specifier}`)
    },
    { wx, pages: [{}, {}] }
  )
  const page = instantiatePage(loaded.definition, {
    prescription: { id: 'prescription-1', totalAmount: 30, status: 'unpaid' },
    payMethod: 'wechat',
    balance: '100.00',
    paying: false
  })

  await page.doPay()
  const executedTimerCount = runAllTimers(loaded.timers)

  assert.strictEqual(payCalls, 1)
  assert.ok(fetchMeCalls >= 1)
  assert.strictEqual(toast.messages.includes('balance-refresh-failed'), false)
  assert.strictEqual(showToastCalls, 1)
  assert.strictEqual(executedTimerCount, 1)
  assert.strictEqual(navigateBackCalls, 1)
})

test('F08修复：App 启动时 fetchMe 网络失败保留登录态', async () => {
  let clearCalls = 0
  let fetchMeCalls = 0
  const auth = {
    getToken: () => 'stored-session-marker',
    getUser: () => ({ nickname: 'tester' }),
    shouldAutoLogin: () => false,
    fetchMe() {
      fetchMeCalls += 1
      return Promise.reject(new Error('网络连接失败：模拟超时'))
    },
    sessionExpired() {
      clearCalls += 1
    }
  }
  const definition = loadApp((specifier) => {
    if (specifier === './utils/auth') return auth
    if (specifier === './utils/privacy') return { setupPrivacyAuthorization() {} }
    throw new Error(`unexpected app dependency: ${specifier}`)
  })
  const app = {
    ...definition,
    globalData: { ...definition.globalData },
    onLaunch: definition.onLaunch
  }

  app.onLaunch.call(app)
  await settle()

  const preserved = fetchMeCalls === 1
    && clearCalls === 0
    && app.globalData.user && app.globalData.user.nickname === 'tester'
  assert.strictEqual(
    preserved,
    true,
    `网络失败不应清除登录态: ${JSON.stringify({ fetchMeCalls, clearCalls })}`
  )
})

test('F12修复：空病历返回 null 时结束 loading 并进入空态', async () => {
  const toast = createToast()
  const { definition } = loadPage(
    'wechat-miniprogram/miniprogram/pages/visit/record.js',
    (specifier) => {
      if (specifier === '../../utils/api') return { getConsultation: () => Promise.resolve(null) }
      if (specifier === '../../utils/toast') return toast
      throw new Error(`unexpected record dependency: ${specifier}`)
    }
  )
  const page = instantiatePage(definition, { items: [], loading: true, empty: false, loadError: false })

  page.onLoad({ id: 'appointment-1' })
  await settle()

  const settled = page.data.loading === false
    && page.data.empty === true
    && page.data.items.length === 0
    && page.data.loadError === false
  assert.strictEqual(
    settled,
    true,
    `空病历应结束 loading 并显示空态: ${JSON.stringify({ data: page.data, toast: toast.messages })}`
  )
})

test('F12修复：订单首页按 size 分页，触底加载下一页且结果不重复', async () => {
  const requests = []
  const all = Array.from({ length: 51 }, (_, index) => ({
    id: String(51 - index),
    status: 'completed',
    scheduleDate: '2026-11-01',
    timeSlot: '09:00'
  }))
  const { definition } = loadPage(
    'wechat-miniprogram/miniprogram/pages/order/list.js',
    (specifier) => {
      if (specifier === '../../utils/tab') return { setTabBar() {} }
      if (specifier === '../../utils/auth') return { isLoggedIn: () => true }
      if (specifier === '../../utils/api') {
        return {
          getAppointments(params) {
            requests.push({ ...params })
            const start = (params.current - 1) * params.size
            return Promise.resolve({ records: all.slice(start, start + params.size), total: all.length })
          }
        }
      }
      if (specifier === '../../utils/adapter') return { toOrder: (value) => value }
      if (specifier === '../../utils/toast') return createToast()
      throw new Error(`unexpected order-list dependency: ${specifier}`)
    }
  )
  const page = instantiatePage(definition, {
    activeTab: 0,
    tabs: definition.data.tabs,
    orders: [],
    loggedIn: true
  })

  page.loadOrders(0)
  await settle()
  assert.strictEqual(page.data.orders.length, 20)
  for (let i = 0; i < 4; i++) {
    page.onReachBottom()
    await settle()
  }

  assert.deepStrictEqual(requests.map((item) => item.current), [1, 2, 3])
  assert.strictEqual(page.data.orders.length, 51)
  assert.strictEqual(new Set(page.data.orders.map((order) => order.id)).size, 51)
  assert.strictEqual(page.data.hasMore, false)
})

test('F12修复：消息通知按 size 分页加载，触底取下一页且无重复', async () => {
  const requests = []
  const all = Array.from({ length: 51 }, (_, index) => ({
    id: index + 1,
    title: `通知 ${index + 1}`,
    read: true,
    createTime: '2026-11-13 10:00:00'
  }))
  const { definition } = loadPage(
    'wechat-miniprogram/miniprogram/pages/mine/notifications/notifications.js',
    (specifier) => {
      if (specifier === '../../../utils/api') {
        return {
          getNotifications(params) {
            requests.push({ ...params })
            const start = (params.current - 1) * params.size
            return Promise.resolve({ records: all.slice(start, start + params.size), total: all.length })
          }
        }
      }
      if (specifier === '../../../utils/auth') return { isLoggedIn: () => true }
      if (specifier === '../../../utils/toast') return createToast()
      if (specifier === '../../../utils/format') return formatMocks()
      throw new Error(`unexpected notification dependency: ${specifier}`)
    }
  )
  const page = instantiatePage(definition, {
    loading: false,
    loadingMore: false,
    hasMore: false,
    loadError: false,
    list: []
  })

  page.loadList()
  await settle()
  assert.strictEqual(page.data.list.length, 20)
  for (let i = 0; i < 4; i++) {
    page.onReachBottom()
    await settle()
  }

  assert.deepStrictEqual(requests.map((item) => item.current), [1, 2, 3])
  assert.strictEqual(page.data.list.length, 51)
  assert.strictEqual(new Set(page.data.list.map((item) => item.id)).size, 51)
  assert.strictEqual(page.data.hasMore, false)
})

test('F12修复：充值历史由服务端按 type=recharge 分页，消费流水不占用页面条数', async () => {
  const requests = []
  const all = Array.from({ length: 30 }, (_, index) => ({
    id: index + 1,
    type: index % 3 === 0 ? 'consume' : 'recharge',
    typeText: index % 3 === 0 ? '消费' : '充值',
    amount: index % 3 === 0 ? -20 : 10,
    createTime: '2026-11-13T10:00:00'
  }))
  const { definition } = loadPage(
    'wechat-miniprogram/miniprogram/pages/recharge/recharge.js',
    (specifier) => {
      if (specifier === '../../utils/auth') {
        return { isLoggedIn: () => true, fetchMe: () => Promise.resolve({ balance: 100 }) }
      }
      if (specifier === '../../utils/wallet-api') {
        return {
          fetchBalanceLogs(params) {
            requests.push({ ...params })
            // 与后端一致：先按 type 过滤，再分页
            const filtered = params.type ? all.filter((item) => item.type === params.type) : all
            const start = (params.current - 1) * params.size
            return Promise.resolve({
              records: filtered.slice(start, start + params.size),
              total: filtered.length,
              current: params.current,
              size: params.size
            })
          },
          recharge: () => Promise.resolve()
        }
      }
      if (specifier === '../../utils/pay') return { PAY_METHODS: [], mockChannelPay: () => Promise.resolve() }
      if (specifier === '../../utils/toast') return createToast()
      if (specifier === '../../utils/format') return formatMocks()
      throw new Error(`unexpected recharge-history dependency: ${specifier}`)
    }
  )
  const page = instantiatePage(definition, { logs: [], logsLoading: false })

  page.loadLogs()
  await settle()
  page.onReachBottom()
  await settle()
  page.onReachBottom()
  await settle()

  assert.deepStrictEqual(requests.map((item) => item.type), ['recharge', 'recharge'])
  assert.deepStrictEqual(requests.map((item) => item.current), [1, 2])
  assert.strictEqual(page.data.logs.length, 20)
  assert.ok(page.data.logs.every((log) => log.type === 'recharge'))
  assert.strictEqual(page.data.logsHasMore, false)
})

test('验证已有防护：正常充值成功反馈只出现一次且只请求一次', async () => {
  const boundary = createRequestBoundary()
  const walletApi = loadCommonJs(
    'wechat-miniprogram/miniprogram/utils/wallet-api.js',
    boundary.requireMock,
    { wx: createWalletStorage() }
  )
  const toast = createToast()
  let fetchMeCalls = 0
  const { definition } = loadPage(
    'wechat-miniprogram/miniprogram/pages/recharge/recharge.js',
    (specifier) => {
      if (specifier === '../../utils/auth') {
        return {
          isLoggedIn: () => true,
          fetchMe() {
            fetchMeCalls += 1
            return Promise.resolve({ balance: 200 })
          }
        }
      }
      if (specifier === '../../utils/wallet-api') return walletApi
      if (specifier === '../../utils/pay') {
        return {
          PAY_METHODS: [{ id: 'wechat', name: '微信支付', desc: '模拟支付' }],
          mockChannelPay: () => Promise.resolve()
        }
      }
      if (specifier === '../../utils/toast') return toast
      if (specifier === '../../utils/format') return formatMocks()
      throw new Error(`unexpected recharge-control dependency: ${specifier}`)
    }
  )
  const page = instantiatePage(definition, {
    rechargeAmount: '100.00',
    payMethod: 'wechat',
    submitting: false
  })

  await page.doRecharge()

  const rechargeRequests = boundary.requests.filter((options) => options.url === '/api/wallet/recharge')
  assert.strictEqual(rechargeRequests.length, 1)
  assert.strictEqual(fetchMeCalls, 1)
  assert.deepStrictEqual(toast.messages, ['充值成功'])
  assert.strictEqual(page.data.submitting, false)
})

test('验证已有防护：正常预约支付成功反馈与跳转各只执行一次', async () => {
  const toast = createToast()
  let showToastCalls = 0
  let redirectCalls = 0
  const wx = {
    showToast() { showToastCalls += 1 },
    redirectTo() { redirectCalls += 1 }
  }
  let createCalls = 0
  let payCalls = 0
  const api = {
    getDoctorById: () => Promise.resolve({ id: 7, departmentName: '内科' }),
    createAppointment() {
      createCalls += 1
      return Promise.resolve({ id: 'appointment-1' })
    },
    payAppointment() {
      payCalls += 1
      return Promise.resolve()
    }
  }
  const loaded = loadPage(
    'wechat-miniprogram/miniprogram/pages/appointment/confirm.js',
    (specifier) => {
      if (specifier === '../../utils/auth') {
        return { isLoggedIn: () => true, fetchMe: () => Promise.resolve({ balance: 80 }) }
      }
      if (specifier === '../../utils/toast') return toast
      if (specifier === '../../utils/api') return api
      if (specifier === '../../utils/adapter') return { enrichDoctor: (value) => value }
      if (specifier === '../../utils/format') return formatMocks()
      if (specifier === '../../utils/pay') {
        return {
          PAY_METHODS: [{ id: 'wechat', name: '微信支付', desc: '模拟支付' }],
          mockChannelPay: () => Promise.resolve(),
          needsPayDrawer: () => false
        }
      }
      throw new Error(`unexpected appointment-control dependency: ${specifier}`)
    },
    { wx }
  )
  const page = instantiatePage(loaded.definition, {
    scheduleId: 101,
    patientName: '测试患者',
    patientPhone: '13800000000',
    payMethod: 'wechat',
    feeAmount: '20.00',
    balance: '100.00',
    submitting: false
  })

  await page.doSubmit()

  assert.strictEqual(createCalls, 1)
  assert.strictEqual(payCalls, 1)
  assert.strictEqual(showToastCalls, 1)
  assert.strictEqual(redirectCalls, 0)
  assert.deepStrictEqual(toast.messages, [])
  assert.strictEqual(loaded.timers.length, 1)
  assert.strictEqual(runAllTimers(loaded.timers), 1)
  assert.strictEqual(redirectCalls, 1)
})

test('验证已有防护：真实 request.js 遇到 401 只清一次会话', async () => {
  let pendingRequest
  let clearCalls = 0
  const wx = {
    getStorageSync: () => '',
    request(options) {
      pendingRequest = options
    }
  }
  const requestModule = loadCommonJs(
    'wechat-miniprogram/miniprogram/utils/request.js',
    (specifier) => {
      if (specifier === './config') return { baseUrl: 'https://example.invalid' }
      if (specifier === './auth') {
        return { sessionExpired() { clearCalls += 1 } }
      }
      throw new Error(`unexpected request dependency: ${specifier}`)
    },
    { wx }
  )

  const pending = requestModule.request({ url: '/boundary' })
  assert.ok(pendingRequest, '应调用 wx.request')
  pendingRequest.success({ statusCode: 401, data: { message: '未登录或登录已过期' } })
  await assert.rejects(pending, /未登录或登录已过期/)
  assert.strictEqual(clearCalls, 1)
})

test('验证已有防护：真实 request.js 普通网络失败本身不清会话', async () => {
  let pendingRequest
  let clearCalls = 0
  const wx = {
    getStorageSync: () => 'stored-session-marker',
    request(options) {
      pendingRequest = options
    }
  }
  const requestModule = loadCommonJs(
    'wechat-miniprogram/miniprogram/utils/request.js',
    (specifier) => {
      if (specifier === './config') return { baseUrl: 'https://example.invalid' }
      if (specifier === './auth') {
        return { sessionExpired() { clearCalls += 1 } }
      }
      throw new Error(`unexpected request dependency: ${specifier}`)
    },
    { wx }
  )

  const pending = requestModule.request({ url: '/boundary' })
  assert.ok(pendingRequest, '应调用 wx.request')
  pendingRequest.fail({ errMsg: 'request:fail timeout' })
  await assert.rejects(pending, /网络连接失败/)
  assert.strictEqual(clearCalls, 0)
})

test('N04修复：订单卡片 select 只导航一次，无效 id 不跳转，连点被锁且导航失败后解锁', () => {
  const navs = []
  let failNext = false
  const toast = createToast()
  const wx = {
    navigateTo(options) {
      if (failNext) {
        failNext = false
        if (options.fail) options.fail({ errMsg: 'navigateTo:fail' })
        return
      }
      navs.push(options.url)
    },
    showToast() {},
    showModal() {}
  }
  const { definition } = loadPage(
    'wechat-miniprogram/miniprogram/pages/order/list.js',
    (specifier) => {
      if (specifier === '../../utils/tab') return { setTabBar() {} }
      if (specifier === '../../utils/auth') return { isLoggedIn: () => true }
      if (specifier === '../../utils/api') return { getAppointments: () => Promise.resolve({ records: [], total: 0 }) }
      if (specifier === '../../utils/adapter') return { toOrder: (value) => value }
      if (specifier === '../../utils/toast') return toast
      throw new Error(`unexpected order-list dependency: ${specifier}`)
    },
    { wx }
  )
  const page = instantiatePage(definition, { activeTab: 0, tabs: definition.data.tabs, orders: [], loggedIn: true })

  page.onOrderTap({ detail: { id: 77 } })
  page.onOrderTap({ detail: { id: 77 } })
  assert.deepStrictEqual(navs, ['/pages/order/detail?id=77'])

  page.onOrderTap({ detail: { id: 'undefined' } })
  page.onOrderTap({ detail: {} })
  assert.deepStrictEqual(navs, ['/pages/order/detail?id=77'])
  assert.ok(toast.messages.length >= 1, '无效 id 应给出提示')

  page.onShow()
  failNext = true
  page.onOrderTap({ detail: { id: 88 } })
  page.onOrderTap({ detail: { id: 88 } })
  assert.deepStrictEqual(navs, ['/pages/order/detail?id=77', '/pages/order/detail?id=88'])
})

test('B1修复：余额未读取成功时选择余额支付不提示余额不足，改为重试读取', async () => {
  const toast = createToast()
  let fetchMeCalls = 0
  let createCalls = 0
  const wx = { showToast() {}, redirectTo() {} }
  const api = {
    getDoctorById: () => Promise.resolve({ id: 7, departmentName: '内科' }),
    createAppointment() {
      createCalls += 1
      return Promise.resolve({ id: 'appointment-1' })
    },
    payAppointment: () => Promise.resolve()
  }
  const loaded = loadPage(
    'wechat-miniprogram/miniprogram/pages/appointment/confirm.js',
    (specifier) => {
      if (specifier === '../../utils/auth') {
        return {
          isLoggedIn: () => true,
          fetchMe() {
            fetchMeCalls += 1
            return Promise.reject(new Error('balance-read-failed'))
          }
        }
      }
      if (specifier === '../../utils/toast') return toast
      if (specifier === '../../utils/api') return api
      if (specifier === '../../utils/adapter') return { enrichDoctor: (value) => value }
      if (specifier === '../../utils/format') return formatMocks()
      if (specifier === '../../utils/pay') {
        return {
          PAY_METHODS: [{ id: 'balance', name: '余额支付', desc: '' }],
          mockChannelPay: () => Promise.resolve(),
          needsPayDrawer: () => false
        }
      }
      throw new Error(`unexpected balance dependency: ${specifier}`)
    },
    { wx }
  )
  const page = instantiatePage(loaded.definition, {
    scheduleId: 101,
    patientName: '测试患者',
    patientPhone: '13800000000',
    payMethod: 'balance',
    feeAmount: '20.00',
    balance: '0.00',
    balanceLoaded: false,
    submitting: false
  })

  await page.doSubmit()
  await settle()

  assert.strictEqual(createCalls, 0)
  assert.strictEqual(toast.messages.includes('余额不足，请先充值'), false)
  assert.ok(fetchMeCalls >= 1, '应重新读取余额')
  assert.strictEqual(page.data.balanceLoaded, false)
})

test('F06修复：切换日期后已选时段立即清空，加载期间提交被拒绝', async () => {
  const requests = []
  const api = {
    getSchedules(params) {
      const pending = deferred()
      requests.push({ params: { ...params }, ...pending })
      return pending.promise
    }
  }
  const adapter = {
    enrichDoctor: (value) => value,
    groupSchedules(schedules) {
      const items = (schedules || []).map((schedule) => ({ time: schedule.timeSlot, available: true, scheduleId: schedule.id }))
      return { morning: items, afternoon: [], hasAnySchedule: items.length > 0, hasAvailable: items.length > 0 }
    }
  }
  const toast = createToast()
  const navs = []
  const wx = {
    showToast(options) { toast(options.title) },
    navigateTo(options) { navs.push(options.url) }
  }
  const { definition } = loadPage(
    'wechat-miniprogram/miniprogram/pages/schedule/detail.js',
    (specifier) => {
      if (specifier === '../../utils/api') return api
      if (specifier === '../../utils/adapter') return adapter
      if (specifier === '../../utils/format') return formatMocks()
      if (specifier === '../../utils/toast') return toast
      throw new Error(`unexpected schedule dependency: ${specifier}`)
    },
    { wx }
  )
  const page = instantiatePage(definition, {
    doctorId: 7,
    doctor: { id: 7 },
    selectedDate: '2026-11-20',
    selectedTime: '',
    selectedScheduleId: 0
  })

  page.loadSlots()
  requests[0].resolve([{ id: 101, timeSlot: '09:00', status: 1, remainQuota: 1 }])
  await settle()
  page.onSlotSelect({ detail: { time: '09:00', scheduleId: 101 } })
  assert.strictEqual(page.data.selectedScheduleId, 101)

  page.onDateChange({ detail: { value: '2026-11-21' } })
  assert.strictEqual(page.data.selectedScheduleId, 0, '切换后应立即清空旧选择')
  assert.strictEqual(page.data.selectedTime, '')
  assert.strictEqual(page.data.slotsLoading, true)

  page.onBook()
  assert.deepStrictEqual(navs, [], '加载期间不得带旧时段进入确认页')

  requests[1].resolve([{ id: 202, timeSlot: '10:00', status: 1, remainQuota: 1 }])
  await settle()
  assert.strictEqual(page.data.slotsLoading, false)
  assert.strictEqual(page.data.selectedScheduleId, 0)
  assert.deepStrictEqual(page.data.morning.map((slot) => slot.scheduleId), [202])
})

test('F08修复：真实 request.js 的 5xx 与超时不清会话，错误附带 status', async () => {
  let pendingRequest
  let clearCalls = 0
  const wx = {
    getStorageSync: () => 'stored-session-marker',
    request(options) { pendingRequest = options }
  }
  const requestModule = loadCommonJs(
    'wechat-miniprogram/miniprogram/utils/request.js',
    (specifier) => {
      if (specifier === './config') return { baseUrl: 'https://example.invalid' }
      if (specifier === './auth') return { sessionExpired() { clearCalls += 1 } }
      throw new Error(`unexpected request dependency: ${specifier}`)
    },
    { wx }
  )

  const pending = requestModule.request({ url: '/boundary' })
  pendingRequest.success({ statusCode: 502, data: '<html>' })
  await assert.rejects(pending, (err) => err.status === 502 && clearCalls === 0)
})

test('F08修复：请求发出后 token 已换新，返回的 401 被忽略且不清除新会话', async () => {
  let pendingRequest
  let clearCalls = 0
  let stored = 'OLD-TOKEN'
  const wx = {
    getStorageSync: (key) => (key === 'token' ? stored : ''),
    request(options) { pendingRequest = options }
  }
  const requestModule = loadCommonJs(
    'wechat-miniprogram/miniprogram/utils/request.js',
    (specifier) => {
      if (specifier === './config') return { baseUrl: 'https://example.invalid' }
      if (specifier === './auth') return { sessionExpired() { clearCalls += 1 } }
      throw new Error(`unexpected request dependency: ${specifier}`)
    },
    { wx }
  )

  const pending = requestModule.request({ url: '/boundary' })
  stored = 'NEW-TOKEN'
  pendingRequest.success({ statusCode: 401, data: { message: '登录已过期' } })
  await assert.rejects(pending, (err) => err.code === 'STALE_TOKEN')
  assert.strictEqual(clearCalls, 0)
})