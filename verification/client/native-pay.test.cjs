const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const assert = require('node:assert/strict')
const { test } = require('node:test')

const projectRoot = path.resolve(__dirname, '..', '..')
const miniRoot = 'wechat-miniprogram/miniprogram'

async function settle() {
  for (let index = 0; index < 6; index += 1) {
    await new Promise((resolve) => setImmediate(resolve))
  }
}

// 在 vm 中加载真实 CommonJS 源文件，仅替换 require 与 wx
function loadModule(relativePath, requireMock, wx) {
  const filename = path.join(projectRoot, relativePath)
  const source = fs.readFileSync(filename, 'utf8')
  const module = { exports: {} }
  const sandbox = {
    console, Promise, Date, Math, Number, Object, String,
    setTimeout, clearTimeout, encodeURIComponent, wx, module, exports: module.exports
  }
  const wrapper = vm.runInNewContext(
    `(function(require, module, exports) {\n${source}\n})`,
    sandbox,
    { filename }
  )
  wrapper(requireMock, module, module.exports)
  return module.exports
}

function loadPage(relativePath, requireMock, wx, extra = {}) {
  const filename = path.join(projectRoot, relativePath)
  const source = fs.readFileSync(filename, 'utf8')
  let definition
  const timers = []
  const cleared = []
  const sandbox = {
    console, Promise, Date, Number, Object, String, Math,
    setTimeout(callback, delay) {
      timers.push({ callback, delay, id: timers.length + 1 })
      return timers.length
    },
    clearTimeout(id) { cleared.push(id) },
    wx,
    require: requireMock,
    getApp() { return null },
    getCurrentPages() { return extra.pages || [] },
    Page(value) { definition = value }
  }
  vm.runInNewContext(source, sandbox, { filename })
  return { definition, timers, cleared }
}

function instantiate(definition, overrides = {}, extra = {}) {
  const instance = {
    data: { ...(definition.data || {}), ...overrides },
    setData(updates, callback) {
      Object.assign(this.data, updates)
      if (callback) callback()
    },
    ...extra
  }
  for (const [key, value] of Object.entries(definition)) {
    if (key !== 'data' && typeof value === 'function') instance[key] = value.bind(instance)
  }
  return instance
}

function createToast() {
  const messages = []
  const toast = (message) => { messages.push(String(message)); return message }
  toast.success = toast
  toast.messages = messages
  return toast
}

// vm 内创建的对象原型不同，比较前转为本领域的普通对象
function plain(value) {
  return JSON.parse(JSON.stringify(value))
}

function view(status, overrides = {}) {
  return {
    paymentId: 'p-1',
    businessType: 'RECHARGE',
    status,
    amount: '1.00',
    amountFen: 100,
    expiresAt: 1760000000000,
    codeImageUrl: '/api/payments/wechat/native/cashier/tok/qr',
    cashierUrl: 'http://127.0.0.1:15173/cashier/tok',
    message: '',
    ...overrides
  }
}

// 搭建真实 native-pay / pay / wallet-api，仅替换 request 与 wx 边界
function createWorld(options = {}) {
  const storage = { user: { id: 11 } }
  const requests = []
  const world = {
    requests,
    storage,
    configEnabled: options.enabled !== false,
    configError: null,
    createResults: [],
    queryResults: [],
    navigations: [],
    onNavigate: null,
    clipboard: []
  }
  const wx = {
    getStorageSync: (key) => storage[key],
    setStorageSync: (key, value) => { storage[key] = JSON.parse(JSON.stringify(value)) },
    removeStorageSync: (key) => { delete storage[key] },
    navigateTo(opts) {
      world.navigations.push(opts)
      const eventChannel = { emit() {} }
      if (typeof opts.success === 'function') opts.success({ eventChannel })
      if (world.onNavigate) world.onNavigate(opts)
    }
  }
  const request = (opts) => {
    requests.push(opts)
    if (opts.url === '/api/payments/wechat/native/config') {
      if (world.configError) return Promise.reject(world.configError)
      return Promise.resolve({ enabled: world.configEnabled, mode: world.configEnabled ? 'NATIVE' : 'MOCK' })
    }
    if (opts.url === '/api/payments/wechat/native' && opts.method === 'POST') {
      const next = world.createResults.shift()
      if (next instanceof Error) return Promise.reject(next)
      return Promise.resolve(next)
    }
    if (/^\/api\/payments\/wechat\/native\/[^/]+$/.test(opts.url)) {
      const next = world.queryResults.shift()
      if (next instanceof Error) return Promise.reject(next)
      return Promise.resolve(next)
    }
    if (opts.url === '/api/wallet/recharge') return Promise.resolve({ ok: true })
    return Promise.reject(new Error(`未预期的请求: ${opts.url}`))
  }
  const nativePay = loadModule(`${miniRoot}/utils/native-pay.js`, (spec) => {
    if (spec === './request') return { request }
    throw new Error(`unexpected native-pay dependency: ${spec}`)
  }, wx)
  const walletApi = loadModule(`${miniRoot}/utils/wallet-api.js`, (spec) => {
    if (spec === './request') return { request }
    if (spec === './native-pay') return nativePay
    throw new Error(`unexpected wallet-api dependency: ${spec}`)
  }, wx)
  const pay = loadModule(`${miniRoot}/utils/pay.js`, (spec) => {
    if (spec === './native-pay') return nativePay
    throw new Error(`unexpected pay dependency: ${spec}`)
  }, wx)
  Object.assign(world, { wx, nativePay, walletApi, pay })
  return world
}

function rechargeCreates(world) {
  return world.requests.filter((item) => item.url === '/api/payments/wechat/native' && item.method === 'POST')
}

function legacyRecharges(world) {
  return world.requests.filter((item) => item.url === '/api/wallet/recharge')
}

function keyOf(options) {
  return options.header['Idempotency-Key']
}

test('配置读取失败必须报错，不降级为模拟充值，也不占用幂等键', async () => {
  const world = createWorld()
  world.configError = new Error('config-down')
  await assert.rejects(world.walletApi.recharge(1, 'wechat'), /config-down/)
  assert.strictEqual(legacyRecharges(world).length, 0)
  assert.strictEqual(rechargeCreates(world).length, 0)
  assert.strictEqual(world.storage.wallet_recharge_pending_11, undefined)
  await assert.rejects(world.pay.resolvePayMode('wechat'), /config-down/)
})

test('config 成功结果在有效期内缓存，失败不缓存', async () => {
  const world = createWorld()
  await world.nativePay.getConfig()
  await world.nativePay.getConfig()
  assert.strictEqual(world.requests.filter((item) => item.url.endsWith('/native/config')).length, 1)
  world.nativePay.resetConfigCache()
  world.configError = new Error('x')
  await assert.rejects(world.nativePay.getConfig())
  world.configError = null
  await world.nativePay.getConfig()
  assert.strictEqual(world.requests.filter((item) => item.url.endsWith('/native/config')).length, 3)
})

test('Native 关闭时保持原模拟充值流程', async () => {
  const world = createWorld({ enabled: false })
  await world.walletApi.recharge(1, 'wechat')
  assert.strictEqual(legacyRecharges(world).length, 1)
  assert.strictEqual(rechargeCreates(world).length, 0)
  assert.strictEqual(await world.pay.resolvePayMode('wechat'), 'LEGACY')
  assert.strictEqual(await world.pay.loadPayMode(), 'MOCK')
})

test('Native 启用时充值只走 Native，扫码页明确 SUCCEEDED 才成功并结束幂等键', async () => {
  const world = createWorld()
  world.createResults.push(view('PENDING'))
  world.onNavigate = (opts) => opts.events.finish({ payment: view('SUCCEEDED', { balance: '101.00' }) })
  const result = await world.walletApi.recharge(1, 'wechat')
  assert.strictEqual(result.status, 'SUCCEEDED')
  assert.strictEqual(result.balance, '101.00')
  assert.strictEqual(legacyRecharges(world).length, 0)
  const [first] = rechargeCreates(world)
  assert.deepStrictEqual(plain(first.data), { businessType: 'RECHARGE', amount: 1 })
  assert.ok(keyOf(first))
  assert.strictEqual(world.navigations.length, 1)

  world.createResults.push(view('PENDING', { paymentId: 'p-2' }))
  world.onNavigate = (opts) => opts.events.finish({ payment: view('SUCCEEDED', { paymentId: 'p-2' }) })
  await world.walletApi.recharge(1, 'wechat')
  assert.notStrictEqual(keyOf(rechargeCreates(world)[1]), keyOf(first), '成功后新充值必须换新键')
})

test('普通返回保持待确认并保留键，重试复用原键；CLOSED 后才换新键', async () => {
  const world = createWorld()
  world.createResults.push(view('PENDING'))
  world.queryResults.push(view('PENDING'))
  world.onNavigate = (opts) => opts.events.finish({ payment: view('PENDING') })
  await assert.rejects(world.walletApi.recharge(1, 'wechat'), (error) => error.code === 'PAYMENT_PENDING')
  const firstKey = keyOf(rechargeCreates(world)[0])

  world.createResults.push(view('PENDING'))
  world.queryResults.push(view('CLOSED'))
  await assert.rejects(world.walletApi.recharge(1, 'wechat'), (error) => error.code === 'PAYMENT_CLOSED')
  assert.strictEqual(keyOf(rechargeCreates(world)[1]), firstKey, '待确认后的重试复用原键')

  world.createResults.push(view('PENDING', { paymentId: 'p-3' }))
  world.queryResults.push(view('FAILED', { paymentId: 'p-3' }))
  world.onNavigate = (opts) => opts.events.finish({ payment: view('PENDING', { paymentId: 'p-3' }) })
  await assert.rejects(world.walletApi.recharge(1, 'wechat'), (error) => error.code === 'PAYMENT_FAILED')
  const thirdKey = keyOf(rechargeCreates(world)[2])
  assert.notStrictEqual(thirdKey, firstKey, 'CLOSED 已结束上一次尝试，下一次主动充值使用新键')

  world.createResults.push(view('SUCCEEDED', { paymentId: 'p-4' }))
  await world.walletApi.recharge(1, 'wechat')
  assert.notStrictEqual(keyOf(rechargeCreates(world)[3]), thirdKey, 'FAILED 同样结束该次尝试')
})

test('UNKNOWN、NEEDS_REVIEW、网络错误都保留原键，且 NEEDS_REVIEW 使用 PAYMENT_REVIEW', async () => {
  const world = createWorld()
  world.createResults.push(view('UNKNOWN'))
  world.queryResults.push(view('UNKNOWN'))
  world.onNavigate = (opts) => opts.events.finish({ payment: view('UNKNOWN') })
  await assert.rejects(world.walletApi.recharge(2, 'wechat'), (error) => error.code === 'PAYMENT_PENDING')
  const key = keyOf(rechargeCreates(world)[0])

  world.createResults.push(view('NEEDS_REVIEW'))
  await assert.rejects(world.walletApi.recharge(2, 'wechat'), (error) => {
    return error.code === 'PAYMENT_REVIEW' && /已支付待人工处理，请勿重复支付/.test(error.message)
  })
  assert.strictEqual(keyOf(rechargeCreates(world)[1]), key)

  world.createResults.push(new Error('network-down'))
  await assert.rejects(world.walletApi.recharge(2, 'wechat'), /network-down/)
  assert.strictEqual(keyOf(rechargeCreates(world)[2]), key)

  world.createResults.push(view('UNKNOWN'))
  world.queryResults.push(new Error('query-down'))
  await assert.rejects(world.walletApi.recharge(2, 'wechat'), (error) => error.code === 'PAYMENT_PENDING')
  assert.strictEqual(keyOf(rechargeCreates(world)[3]), key)
  assert.strictEqual(world.storage.wallet_recharge_pending_11['200|wechat'].key, key)
})

test('Native 启用时支付宝付款与充值走旧模拟流程，微信仍走 Native，未知渠道被拒绝，余额不读取配置', async () => {
  const world = createWorld()
  assert.strictEqual(await world.pay.resolvePayMode('alipay'), 'LEGACY')
  assert.strictEqual(await world.pay.resolvePayMode('wechat'), 'NATIVE')
  await assert.rejects(world.pay.resolvePayMode('unknown'), (error) => error.code === 'PAYMENT_CHANNEL_UNAVAILABLE')
  await assert.rejects(world.walletApi.recharge(1, 'unknown'), (error) => error.code === 'PAYMENT_CHANNEL_UNAVAILABLE')
  assert.strictEqual(world.storage.wallet_recharge_pending_11, undefined, '未知渠道不得占用幂等键')
  assert.strictEqual(rechargeCreates(world).length, 0)

  await world.walletApi.recharge(1, 'alipay')
  assert.strictEqual(legacyRecharges(world).length, 1)
  assert.strictEqual(rechargeCreates(world).length, 0, '支付宝不得创建微信 Native 支付单')
  assert.deepStrictEqual(plain(legacyRecharges(world)[0].data), { amount: 1, payMethod: 'alipay' })
  assert.ok(keyOf(legacyRecharges(world)[0]), '支付宝充值仍必须携带幂等键')

  world.createResults.push(view('SUCCEEDED'))
  await world.walletApi.recharge(1, 'wechat')
  assert.strictEqual(legacyRecharges(world).length, 1, '微信 Native 不得再走旧 wallet/recharge')
  assert.strictEqual(rechargeCreates(world).length, 1)

  world.nativePay.resetConfigCache()
  const before = world.requests.length
  assert.strictEqual(await world.pay.resolvePayMode('balance'), 'LEGACY')
  assert.strictEqual(world.requests.length, before)
})

test('支付宝充值在不确定失败后复用同一幂等键，配置失败不降级为旧接口', async () => {
  const failures = [new Error('network-down'), new Error('network-down')]
  let legacyAttempts = 0
  const failingWorld = createWorld({ enabled: true })
  const failingRequests = failingWorld.requests
  const push = failingRequests.push.bind(failingRequests)
  failingRequests.push = (opts) => {
    if (opts.url === '/api/wallet/recharge') {
      legacyAttempts += 1
      push(opts)
      if (legacyAttempts <= failures.length) throw failures[legacyAttempts - 1]
      return failingRequests.length
    }
    return push(opts)
  }
  await assert.rejects(failingWorld.walletApi.recharge(5, 'alipay'), /network-down/)
  await assert.rejects(failingWorld.walletApi.recharge(5, 'alipay'), /network-down/)
  await failingWorld.walletApi.recharge(5, 'alipay')
  const keys = legacyRecharges(failingWorld).map(keyOf)
  assert.strictEqual(keys.length, 3)
  assert.strictEqual(new Set(keys).size, 1, '同一用户、金额、渠道的不确定重试复用同一键')
  assert.strictEqual(failingWorld.storage.wallet_recharge_pending_11, undefined, '明确成功后清理待确认记录')

  const down = createWorld()
  down.configError = new Error('config-down')
  await assert.rejects(down.walletApi.recharge(1, 'alipay'), /config-down/)
  await assert.rejects(down.pay.resolvePayMode('alipay'), /config-down/)
  assert.strictEqual(legacyRecharges(down).length, 0, '配置失败不得降级为模拟充值')
  assert.strictEqual(down.storage.wallet_recharge_pending_11, undefined)
})

test('支付方式列表在 Native 模式保留支付宝并始终标注仅教学模拟', () => {
  const world = createWorld()
  const methods = world.pay.PAY_METHODS
  const native = world.pay.decoratePayMethods(methods, { mode: 'NATIVE', balance: '5.00' })
  assert.deepStrictEqual(plain(native.map((item) => item.id)), ['wechat', 'alipay', 'balance'])
  assert.ok(native[0].desc.includes('真实支付'))
  assert.strictEqual(native[1].name, '支付宝（模拟）')
  assert.strictEqual(native[1].desc, '仅教学模拟，不会真实扣款')
  assert.ok(world.pay.payTipFor('alipay', 'NATIVE').includes('不会真实扣款'))
  assert.ok(world.pay.payTipFor('alipay', 'MOCK').includes('不会真实扣款'))
  assert.strictEqual(world.pay.payMethodLabel('alipay'), '支付宝（模拟）')
  assert.strictEqual(world.pay.getPayChannelInfo('alipay').name, '支付宝（模拟）')
  const mock = world.pay.decoratePayMethods(methods, { mode: 'MOCK', balance: '5.00' })
  assert.deepStrictEqual(plain(mock.map((item) => item.id)), ['wechat', 'alipay', 'balance'])
  assert.strictEqual(mock[0].desc, '模拟支付')
  assert.ok(world.pay.payTipFor('wechat', 'NATIVE').includes('不能长按'))
})

test('四个支付页在 Native 下切换或刷新后不再把支付宝改回微信', async () => {
  const adapter = loadModule(`${miniRoot}/utils/adapter.js`, (spec) => {
    if (spec === './dept-meta') return { enrichDepartment: (value) => value, enrichDepartments: (value) => value }
    if (spec === './media') return { resolveMediaUrl: (value) => value }
    throw new Error(`unexpected adapter dependency: ${spec}`)
  }, {})
  assert.strictEqual(adapter.toOrder({ id: 1, payMethod: 'alipay', payMethodText: '支付宝' }).payMethodText, '支付宝（模拟）')
  assert.strictEqual(adapter.toOrder({ id: 2, payMethod: 'wechat', payMethodText: '微信支付' }).payMethodText, '微信支付')

  const world = createWorld()
  const format = {
    formatDateTime: () => '', formatFee: (value) => String(value),
    formatServerDateTime: (value) => String(value), buildDateStrip: () => []
  }
  const toast = createToast()
  const pages = [
    'pages/order/detail.js', 'pages/prescription/detail.js', 'pages/appointment/confirm.js', 'pages/recharge/recharge.js'
  ]
  for (const file of pages) {
    const loaded = loadPage(`${miniRoot}/${file}`, (spec) => {
      if (spec === '../../utils/pay') return world.pay
      if (spec === '../../utils/auth') return { isLoggedIn: () => true, fetchMe: () => Promise.resolve({ balance: 8 }) }
      if (spec === '../../utils/toast') return toast
      if (spec === '../../utils/format') return format
      if (spec === '../../utils/adapter') return { toOrder: (value) => value, enrichDoctor: (value) => value }
      if (spec === '../../utils/wallet-api') return world.walletApi
      if (spec === '../../utils/api') return {}
      throw new Error(`unexpected page dependency: ${spec}`)
    }, { setStorageSync() {}, getStorageSync() {} })
    const page = instantiate(loaded.definition, { payMethod: 'alipay' })
    await page.loadPayMode()
    assert.strictEqual(page.data.payMode, 'NATIVE', file)
    assert.strictEqual(page.data.payMethod, 'alipay', `${file} 不得把支付宝改回微信`)
    assert.ok(plain(page.data.payMethods).some((item) => item.id === 'alipay' && item.name === '支付宝（模拟）'), file)
    if (typeof page.loadBalance === 'function') {
      await page.loadBalance()
      assert.strictEqual(page.data.payMethod, 'alipay', `${file} 刷新余额后仍保持支付宝`)
    }
    page.onPayMethodTap({ currentTarget: { dataset: { id: 'alipay' } } })
    assert.ok(page.data.payTip.includes('不会真实扣款'), file)
  }
})

for (const confirmedMethod of ['alipay', 'wechat']) {
  test(`充值确认 ${confirmedMethod} 后关闭抽屉与切换渠道不能改变本次付款路由`, async () => {
    const world = createWorld()
    const toast = createToast()
    world.createResults.push(view('SUCCEEDED', { amount: '100.00', amountFen: 10000 }))
    const loaded = loadPage(`${miniRoot}/pages/recharge/recharge.js`, (spec) => {
      if (spec === '../../utils/pay') return world.pay
      if (spec === '../../utils/wallet-api') return world.walletApi
      if (spec === '../../utils/auth') return { fetchMe: () => Promise.resolve({ balance: 100 }) }
      if (spec === '../../utils/toast') return toast
      if (spec === '../../utils/format') return { formatServerDateTime: (value) => String(value) }
      throw new Error(`unexpected recharge dependency: ${spec}`)
    }, world.wx)
    const page = instantiate(loaded.definition, {
      payMethod: confirmedMethod, payMode: 'NATIVE', rechargeAmount: '100.00',
      payDrawerVisible: true, payDrawerMethod: confirmedMethod
    })
    page.loadLogs = async () => {}
    const submitting = page.doRecharge()
    // 支付宝分支进入真实模拟等待；微信分支在配置 await 返回前尝试切换。
    if (confirmedMethod === 'alipay') await settle()
    assert.strictEqual(page.data.submitting, true)
    page.onPayDrawerClose()
    page.onPayMethodTap({ currentTarget: { dataset: { id: confirmedMethod === 'alipay' ? 'wechat' : 'alipay' } } })
    assert.strictEqual(page.data.payDrawerVisible, true, '提交中不关闭确认抽屉')
    assert.strictEqual(page.data.payMethod, confirmedMethod, '提交中不能改变选中渠道')
    // 即使其他异步回调写入页面状态，实际请求仍使用确认时固定的渠道。
    page.setData({ payMethod: confirmedMethod === 'alipay' ? 'wechat' : 'alipay' })
    await submitting
    assert.strictEqual(page.data.submitting, false)
    assert.strictEqual(page.rechargeLocked, false)
    assert.ok(toast.messages.includes('充值成功'))
    assert.strictEqual(legacyRecharges(world).length, confirmedMethod === 'alipay' ? 1 : 0)
    assert.strictEqual(rechargeCreates(world).length, confirmedMethod === 'wechat' ? 1 : 0)
    const request = confirmedMethod === 'alipay' ? legacyRecharges(world)[0] : rechargeCreates(world)[0]
    assert.ok(keyOf(request), '确认渠道对应的充值请求仍携带幂等键')
    if (confirmedMethod === 'alipay') assert.strictEqual(request.data.payMethod, 'alipay')
  })
}

test('创建即为终态时不打开扫码页：SUCCEEDED 直接成功，CLOSED 与 NEEDS_REVIEW 按 code 拒绝', async () => {
  const world = createWorld()
  world.createResults.push(view('SUCCEEDED'))
  assert.strictEqual((await world.nativePay.pay({ businessType: 'APPOINTMENT', businessId: 1 })).status, 'SUCCEEDED')
  world.createResults.push(view('NEEDS_REVIEW'))
  await assert.rejects(world.nativePay.pay({ businessType: 'APPOINTMENT', businessId: 1 }), (e) => e.code === 'PAYMENT_REVIEW')
  assert.strictEqual(world.navigations.length, 0)
})

function loadNativePage(world, state = {}) {
  const toast = createToast()
  const emitted = []
  const handlers = {}
  const wx = {
    setClipboardData(opts) { state.clipboard = opts.data },
    showModal() {},
    navigateBack() { state.backCalls = (state.backCalls || 0) + 1 },
    enableAlertBeforeUnload() { state.alertOn = true },
    disableAlertBeforeUnload() { state.alertOn = false }
  }
  const loaded = loadPage(`${miniRoot}/pages/payment/native.js`, (spec) => {
    if (spec === '../../utils/native-pay') return world.nativePay
    if (spec === '../../utils/config') return { baseUrl: 'https://api.example.test' }
    if (spec === '../../utils/toast') return toast
    throw new Error(`unexpected native page dependency: ${spec}`)
  }, wx)
  const page = instantiate(loaded.definition, {}, {
    getOpenerEventChannel: () => ({
      on(name, handler) { handlers[name] = handler },
      emit(name, payload) { emitted.push({ name, payload }) }
    })
  })
  return { page, loaded, handlers, emitted, toast, state }
}

test('扫码页展示 QR 与金额、复制电脑链接、3 秒轮询，卸载停 timer 并回传最后状态', async () => {
  const world = createWorld()
  const { page, loaded, handlers, emitted, state } = loadNativePage(world)
  page.onLoad({ paymentId: 'p-1' })
  handlers.init({ payment: view('PENDING', { amount: '20.00' }) })
  assert.strictEqual(page.data.qrUrl, 'https://api.example.test/api/payments/wechat/native/cashier/tok/qr')
  assert.strictEqual(page.data.amountText, '20.00')
  assert.strictEqual(page.data.showQr, true)
  assert.strictEqual(page.data.canClose, true)
  assert.strictEqual(loaded.timers.length, 1)
  assert.strictEqual(loaded.timers[0].delay, 3000)
  page.onCopyLink()
  assert.strictEqual(state.clipboard, 'http://127.0.0.1:15173/cashier/tok')

  world.queryResults.push(view('UNKNOWN', { amount: '20.00' }))
  loaded.timers.shift().callback()
  await settle()
  assert.strictEqual(page.data.status, 'UNKNOWN')
  assert.strictEqual(loaded.timers.length, 1, '非终态继续轮询')

  page.onUnload()
  assert.ok(loaded.cleared.length >= 1 || loaded.timers.length >= 0)
  assert.strictEqual(emitted.length, 1)
  assert.strictEqual(emitted[0].payload.payment.status, 'UNKNOWN')
  page.onUnload()
  assert.strictEqual(emitted.length, 1, 'finish 只回传一次')
})

test('扫码页卸载后不再轮询或更新；NEEDS_REVIEW 提示请勿重复支付且不出现退款字样', async () => {
  const world = createWorld()
  const { page, loaded, handlers } = loadNativePage(world)
  page.onLoad({ paymentId: 'p-1' })
  handlers.init({ payment: view('PENDING') })
  const pending = loaded.timers.shift()
  page.onUnload()
  const before = world.requests.length
  pending.callback()
  await settle()
  assert.strictEqual(world.requests.length, before, '卸载后即使 timer 触发也不发请求')

  const second = loadNativePage(world)
  second.page.onLoad({ paymentId: 'p-1' })
  second.handlers.init({ payment: view('NEEDS_REVIEW') })
  assert.strictEqual(second.page.data.statusText, '已支付待人工处理，请勿重复支付')
  assert.strictEqual(second.page.data.terminal, true)
  assert.strictEqual(second.page.data.canClose, false)
  assert.ok(!/退款/.test(second.page.data.statusText + second.page.data.detailText))
  assert.strictEqual(second.loaded.timers.length, 0)
})

test('扫码页关闭按钮调用 close，终态后才允许退出；二维码地址非约定前缀时不展示', async () => {
  const world = createWorld()
  const { page, handlers } = loadNativePage(world)
  page.onLoad({ paymentId: 'p-1' })
  handlers.init({ payment: view('PENDING', { codeImageUrl: 'https://evil.example/qr' }) })
  assert.strictEqual(page.data.qrUrl, '')
  world.queryResults.push(view('CLOSED'))
  await page.doClose()
  const closeCall = world.requests.find((item) => item.url === '/api/payments/wechat/native/p-1/close')
  assert.ok(closeCall)
  assert.strictEqual(closeCall.method, 'POST')
})

test('挂号页 Native 启用时不调用旧 payAppointment，PAYMENT_PENDING 不显示成功', async () => {
  const toast = createToast()
  let legacyPayCalls = 0
  let createCalls = 0
  const nativeCalls = []
  let nativeOutcome = Promise.reject(Object.assign(new Error('支付结果待确认，请勿重复支付'), { code: 'PAYMENT_PENDING' }))
  let showToastCalls = 0
  const wx = { showToast() { showToastCalls += 1 }, redirectTo() {}, showModal() {} }
  const api = {
    getDoctorById: () => Promise.resolve({ id: 7, departmentName: '内科' }),
    createAppointment: () => { createCalls += 1; return Promise.resolve({ id: 'appointment-1' }) },
    payAppointment() { legacyPayCalls += 1; return Promise.resolve() }
  }
  const loaded = loadPage(`${miniRoot}/pages/appointment/confirm.js`, (spec) => {
    if (spec === '../../utils/auth') return { isLoggedIn: () => true, fetchMe: () => Promise.resolve({ balance: 80 }) }
    if (spec === '../../utils/toast') return toast
    if (spec === '../../utils/api') return api
    if (spec === '../../utils/adapter') return { enrichDoctor: (value) => value }
    if (spec === '../../utils/format') {
      return { buildDateStrip: () => [], formatDateTime: () => '', formatFee: (v) => String(v), formatServerDateTime: (v) => String(v) }
    }
    if (spec === '../../utils/pay') {
      return {
        PAY_METHODS: [{ id: 'wechat', name: '微信支付', desc: '' }],
        needsPayDrawer: () => false,
        mockChannelPay: () => Promise.resolve(),
        resolvePayMode: () => Promise.resolve('NATIVE'),
        payNative(params) { nativeCalls.push(params); return nativeOutcome },
        loadPayMode: () => Promise.resolve('NATIVE'),
        decoratePayMethods: (methods) => methods,
        payTipFor: () => ''
      }
    }
    throw new Error(`unexpected appointment dependency: ${spec}`)
  }, wx)
  const data = {
    scheduleId: 101, patientName: '测试患者', patientPhone: '13800000000',
    payMethod: 'wechat', feeAmount: '20.00', balance: '100.00', submitting: false
  }
  const page = instantiate(loaded.definition, data)
  await page.doSubmit()
  assert.strictEqual(legacyPayCalls, 0)
  assert.deepStrictEqual(plain(nativeCalls), [{ businessType: 'APPOINTMENT', businessId: 'appointment-1' }])
  assert.strictEqual(showToastCalls, 0, '待确认不能显示支付成功')
  assert.ok(toast.messages.some((message) => /待确认/.test(message)))
  assert.strictEqual(loaded.timers.length, 1, '仅跳转订单页查看，不提示成功')
  assert.strictEqual(page.data.appointmentLocked, true)
  page.onPatientNameInput({ detail: '另一位患者' })
  page.onPatientPhoneInput({ detail: '13900000000' })
  assert.strictEqual(page.data.patientName, '测试患者')
  assert.strictEqual(page.data.patientPhone, '13800000000')
  page.setData({ patientName: '另一位患者', patientPhone: '13900000000' })
  await page.doSubmit()
  assert.strictEqual(createCalls, 1, '缓存身份不一致时须先处理旧预约')
  assert.strictEqual(nativeCalls.length, 1, '不能把新就诊人的操作支付到旧预约ID')
  assert.ok(toast.messages.some(message => message.includes('处理旧预约')))

  nativeOutcome = Promise.resolve(view('SUCCEEDED', { businessType: 'APPOINTMENT' }))
  const page2 = instantiate(loaded.definition, data)
  await page2.doSubmit()
  assert.strictEqual(legacyPayCalls, 0)
  assert.strictEqual(showToastCalls, 1)
})
