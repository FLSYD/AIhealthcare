const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const assert = require('node:assert/strict')
const { test } = require('node:test')

const projectRoot = path.resolve(__dirname, '..', '..')
const miniRoot = 'wechat-miniprogram/miniprogram'

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
  for (let index = 0; index < 5; index += 1) {
    await new Promise((resolve) => setImmediate(resolve))
  }
}

// 在 vm 中加载真实的 CommonJS 源文件，仅替换 require 与 wx
function loadModule(relativePath, requireMock, wx) {
  const filename = path.join(projectRoot, relativePath)
  const source = fs.readFileSync(filename, 'utf8')
  const module = { exports: {} }
  const sandbox = {
    console,
    Promise,
    Date,
    Math,
    Number,
    Object,
    String,
    setTimeout,
    clearTimeout,
    wx,
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

// 默认 Native 关闭（MOCK），保持原充值请求路径与幂等断言不变
function createNativeStub() {
  return {
    getConfig: () => Promise.resolve({ enabled: false, mode: 'MOCK' }),
    pay: () => Promise.reject(new Error('测试默认不应走 Native'))
  }
}

function loadPage(relativePath, requireMock, wx) {
  const filename = path.join(projectRoot, relativePath)
  const source = fs.readFileSync(filename, 'utf8')
  let definition
  const sandbox = {
    console,
    Promise,
    Date,
    Number,
    Object,
    setTimeout() { return 0 },
    clearTimeout() {},
    wx,
    require: requireMock,
    getApp() { return null },
    getCurrentPages() { return [] },
    Page(value) { definition = value }
  }
  vm.runInNewContext(source, sandbox, { filename })
  return definition
}

function instantiatePage(definition, dataOverrides = {}) {
  const instance = {
    data: { ...(definition.data || {}), ...dataOverrides },
    setData(patch) { Object.assign(this.data, patch) }
  }
  Object.keys(definition).forEach((name) => {
    if (name !== 'data') instance[name] = definition[name]
  })
  return instance
}

// 本地存储（按进程内共享，模拟页面销毁后仍保留）与 wx.request 替身
function createWx(initialStorage = {}) {
  const storage = { ...initialStorage }
  const requests = []
  const wx = {
    storage,
    requests,
    failures: { get: false, set: false, remove: false, silentSet: false, silentRemove: false },
    getStorageSync(key) {
      if (wx.failures.get && key.startsWith('wallet_recharge_pending_')) throw new Error('storage get failed')
      return storage[key]
    },
    setStorageSync(key, value) {
      if (wx.failures.set && key.startsWith('wallet_recharge_pending_')) throw new Error('storage set failed')
      if (wx.failures.silentSet && key.startsWith('wallet_recharge_pending_')) return
      storage[key] = JSON.parse(JSON.stringify(value))
    },
    removeStorageSync(key) {
      if (wx.failures.remove && key.startsWith('wallet_recharge_pending_')) throw new Error('storage remove failed')
      if (wx.failures.silentRemove && key.startsWith('wallet_recharge_pending_')) return
      delete storage[key]
    },
    request(options) {
      requests.push(options)
      wx.onRequest(options)
    },
    onRequest() {},
    showToast() {},
    showLoading() {},
    hideToast() {},
    hideLoading() {}
  }
  return wx
}

function respondOk(options, data = 100) {
  options.success({ statusCode: 200, data: { code: 200, data } })
}

function respondNetworkFail(options) {
  options.fail({ errMsg: 'request:fail timeout' })
}

function loadWalletApi(wx) {
  const request = loadModule(`${miniRoot}/utils/request.js`, (specifier) => {
    if (specifier === './config') return { baseUrl: 'http://test' }
    throw new Error(`unexpected request dependency: ${specifier}`)
  }, wx)
  return loadModule(`${miniRoot}/utils/wallet-api.js`, (specifier) => {
    if (specifier === './request') return request
    if (specifier === './native-pay') return createNativeStub()
    throw new Error(`unexpected wallet-api dependency: ${specifier}`)
  }, wx)
}

function keyOf(options) {
  return options.header && options.header['Idempotency-Key']
}

function createToast() {
  const messages = []
  const toast = (message) => { messages.push(message) }
  toast.success = (message) => { messages.push(message) }
  toast.loading = () => {}
  toast.clear = () => {}
  toast.messages = messages
  return toast
}

function loadRechargePage(wx, options = {}) {
  const walletApi = loadWalletApi(wx)
  const toast = createToast()
  const counters = { fetchMe: 0, channelPay: 0 }
  const definition = loadPage(`${miniRoot}/pages/recharge/recharge.js`, (specifier) => {
    if (specifier === '../../utils/auth') {
      return {
        isLoggedIn: () => true,
        fetchMe() {
          counters.fetchMe += 1
          return options.fetchMe ? options.fetchMe() : Promise.resolve({ balance: 200 })
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
          counters.channelPay += 1
          return Promise.resolve()
        },
        resolvePayMode: () => Promise.resolve('LEGACY'),
        loadPayMode: () => Promise.resolve('MOCK'),
        decoratePayMethods: (methods) => methods,
        payTipFor: () => ''
      }
    }
    if (specifier === '../../utils/toast') return toast
    if (specifier === '../../utils/format') return { formatServerDateTime: (value) => String(value) }
    throw new Error(`unexpected recharge dependency: ${specifier}`)
  }, wx)
  const page = instantiatePage(definition, {
    rechargeAmount: '100.00',
    payMethod: 'wechat',
    submitting: false
  })
  return { page, toast, counters, walletApi }
}

const USER_A = { user: { id: 11, nickname: 'a' }, token: 'token-a' }

test('同一次不确定重试携带相同 Idempotency-Key，明确成功后新充值使用新键', async () => {
  const wx = createWx(USER_A)
  const walletApi = loadWalletApi(wx)

  wx.onRequest = respondNetworkFail
  await assert.rejects(walletApi.recharge(100, 'wechat'))
  wx.onRequest = respondNetworkFail
  await assert.rejects(walletApi.recharge(100, 'wechat'))
  wx.onRequest = (options) => respondOk(options)
  await walletApi.recharge(100, 'wechat')

  assert.strictEqual(wx.requests.length, 3)
  const keys = wx.requests.map(keyOf)
  assert.ok(keys[0] && keys[0].length > 0 && keys[0].length <= 200, '键应非空且不超过后端 200 字符上限')
  assert.deepStrictEqual(keys, [keys[0], keys[0], keys[0]])
  assert.strictEqual(wx.requests[0].url, 'http://test/api/wallet/recharge')
  assert.strictEqual(JSON.stringify(wx.requests[0].data), JSON.stringify({ amount: 100, payMethod: 'wechat' }))

  wx.onRequest = (options) => respondOk(options)
  await walletApi.recharge(100, 'wechat')
  assert.strictEqual(wx.requests.length, 4)
  assert.notStrictEqual(keyOf(wx.requests[3]), keys[0], '新的主动充值不得沿用已成功的旧键')
  assert.strictEqual(wx.storage.wallet_recharge_pending_11, undefined, '成功后不应残留待确认记录')
})

test('服务端业务拒绝或响应异常同样保留键，仅明确成功才结束', async () => {
  const wx = createWx(USER_A)
  const walletApi = loadWalletApi(wx)

  wx.onRequest = (options) => options.success({ statusCode: 502, data: null })
  await assert.rejects(walletApi.recharge(50, 'alipay'))
  wx.onRequest = (options) => options.success({ statusCode: 200, data: { code: 500, message: 'x' } })
  await assert.rejects(walletApi.recharge(50, 'alipay'))
  wx.onRequest = (options) => respondOk(options)
  await walletApi.recharge(50, 'alipay')

  const keys = wx.requests.map(keyOf)
  assert.strictEqual(new Set(keys).size, 1)
})

test('金额或渠道变化使用新键，不误复用', async () => {
  const wx = createWx(USER_A)
  const walletApi = loadWalletApi(wx)

  wx.onRequest = respondNetworkFail
  await assert.rejects(walletApi.recharge(100, 'wechat'))
  await assert.rejects(walletApi.recharge(200, 'wechat'))
  await assert.rejects(walletApi.recharge(100, 'alipay'))
  const keys = wx.requests.map(keyOf)
  assert.strictEqual(new Set(keys).size, 3, '三种不同金额/渠道应各用独立键')

  wx.onRequest = respondNetworkFail
  await assert.rejects(walletApi.recharge(100, 'wechat'))
  assert.strictEqual(keyOf(wx.requests[3]), keys[0], '回到原金额与渠道的未确认尝试应复用原键')
})

test('同一金额的浮点表示一致地映射到同一键', async () => {
  const wx = createWx(USER_A)
  const walletApi = loadWalletApi(wx)
  wx.onRequest = respondNetworkFail
  await assert.rejects(walletApi.recharge(0.1 + 0.2, 'wechat'))
  await assert.rejects(walletApi.recharge(0.3, 'wechat'))
  assert.strictEqual(keyOf(wx.requests[0]), keyOf(wx.requests[1]))
})

test('并发点击只发出一次请求，同参数共享结果，不同金额被拒绝', async () => {
  const wx = createWx(USER_A)
  const walletApi = loadWalletApi(wx)
  const pendingResponses = []
  wx.onRequest = (options) => pendingResponses.push(options)

  const first = walletApi.recharge(100, 'wechat')
  const second = walletApi.recharge(100, 'wechat')
  await assert.rejects(walletApi.recharge(200, 'wechat'), /正在处理/)
  assert.strictEqual(wx.requests.length, 1)

  respondOk(pendingResponses[0], 300)
  assert.strictEqual(await first, 300)
  assert.strictEqual(await second, 300)
  assert.strictEqual(wx.requests.length, 1)

  wx.onRequest = (options) => respondOk(options)
  await walletApi.recharge(200, 'wechat')
  assert.strictEqual(wx.requests.length, 2)
})

test('页面快速连点 doRecharge 只发出一个充值请求', async () => {
  const wx = createWx(USER_A)
  const pendingResponses = []
  wx.onRequest = (options) => {
    if (options.url.endsWith('/api/wallet/recharge')) pendingResponses.push(options)
  }
  const { page, counters } = loadRechargePage(wx)

  const first = page.doRecharge()
  const second = page.doRecharge()
  const third = page.doRecharge()
  await settle()
  assert.strictEqual(pendingResponses.length, 1)
  respondOk(pendingResponses[0], 300)
  await Promise.all([first, second, third])

  assert.strictEqual(wx.requests.filter((o) => o.url.endsWith('/api/wallet/recharge')).length, 1)
  assert.strictEqual(counters.channelPay, 1)
  assert.strictEqual(page.data.submitting, false)
})

test('充值已确认后 fetchMe 失败只提示成功，不报充值失败也不重复充值', async () => {
  const wx = createWx(USER_A)
  wx.onRequest = (options) => {
    if (options.url.endsWith('/api/wallet/recharge')) respondOk(options, 300)
    else options.success({ statusCode: 200, data: { code: 200, data: { records: [] } } })
  }
  const { page, toast, counters } = loadRechargePage(wx, {
    fetchMe: () => Promise.reject(new Error('fetch-me-failed'))
  })

  await page.doRecharge()
  await settle()

  const rechargeRequests = wx.requests.filter((o) => o.url.endsWith('/api/wallet/recharge'))
  assert.strictEqual(rechargeRequests.length, 1)
  assert.strictEqual(counters.fetchMe, 1)
  assert.ok(toast.messages.includes('充值成功'))
  assert.ok(!toast.messages.includes('fetch-me-failed'))
  assert.ok(!toast.messages.includes('充值失败'))
  assert.ok(toast.messages.some((message) => /充值成功.*余额刷新失败/.test(message)))
  assert.strictEqual(page.data.submitting, false)
  assert.strictEqual(page.data.payDrawerVisible, false)
  assert.strictEqual(wx.storage.wallet_recharge_pending_11, undefined)

  wx.onRequest = (options) => respondOk(options, 400)
  await page.doRecharge()
  const keys = wx.requests.filter((o) => o.url.endsWith('/api/wallet/recharge')).map(keyOf)
  assert.strictEqual(keys.length, 2)
  assert.notStrictEqual(keys[0], keys[1], '确认成功后的新充值必须使用新键')
})

test('充值请求失败时提示失败原因，重试复用同一键并可成功', async () => {
  const wx = createWx(USER_A)
  const { page, toast } = loadRechargePage(wx)

  wx.onRequest = respondNetworkFail
  await page.doRecharge()
  assert.ok(toast.messages.some((message) => /网络连接失败/.test(message)))
  assert.strictEqual(page.data.submitting, false)

  wx.onRequest = (options) => {
    if (options.url.endsWith('/api/wallet/recharge')) respondOk(options, 300)
    else options.success({ statusCode: 200, data: { code: 200, data: { records: [] } } })
  }
  await page.doRecharge()
  const keys = wx.requests.filter((o) => o.url.endsWith('/api/wallet/recharge')).map(keyOf)
  assert.strictEqual(keys.length, 2)
  assert.strictEqual(keys[0], keys[1])
  assert.ok(toast.messages.includes('上次充值已确认'))
})

test('页面重建后同用户同参数重试复用待确认键', async () => {
  const wx = createWx(USER_A)
  const firstPage = loadRechargePage(wx)
  wx.onRequest = respondNetworkFail
  await firstPage.page.doRecharge()

  const rebuilt = loadRechargePage(wx)
  wx.onRequest = (options) => {
    if (options.url.endsWith('/api/wallet/recharge')) respondOk(options, 300)
    else options.success({ statusCode: 200, data: { code: 200, data: { records: [] } } })
  }
  await rebuilt.page.doRecharge()

  const keys = wx.requests.filter((o) => o.url.endsWith('/api/wallet/recharge')).map(keyOf)
  assert.strictEqual(keys.length, 2)
  assert.strictEqual(keys[0], keys[1])
})

test('不同用户的待确认键相互隔离，切换用户不会沿用他人的键', async () => {
  const wx = createWx(USER_A)
  const walletApi = loadWalletApi(wx)
  wx.onRequest = respondNetworkFail
  await assert.rejects(walletApi.recharge(100, 'wechat'))
  const keyA = keyOf(wx.requests[0])

  wx.storage.user = { id: 22, nickname: 'b' }
  wx.storage.token = 'token-b'
  await assert.rejects(walletApi.recharge(100, 'wechat'))
  const keyB = keyOf(wx.requests[1])
  assert.notStrictEqual(keyB, keyA)
  assert.ok(wx.storage.wallet_recharge_pending_11)
  assert.ok(wx.storage.wallet_recharge_pending_22)

  wx.onRequest = (options) => respondOk(options)
  await walletApi.recharge(100, 'wechat')
  assert.strictEqual(keyOf(wx.requests[2]), keyB)
  assert.strictEqual(wx.storage.wallet_recharge_pending_22, undefined)
  assert.ok(wx.storage.wallet_recharge_pending_11, 'B 成功不应清除 A 的待确认记录')

  wx.storage.user = { id: 11, nickname: 'a' }
  wx.onRequest = respondNetworkFail
  await assert.rejects(walletApi.recharge(100, 'wechat'))
  assert.strictEqual(keyOf(wx.requests[3]), keyA, '切回 A 后应复用 A 自己未确认的键')
})

test('没有登录用户 id 时拒绝充值且不发请求', async () => {
  const wx = createWx({})
  const walletApi = loadWalletApi(wx)
  await assert.rejects(walletApi.recharge(100, 'wechat'), /请先登录/)
  assert.strictEqual(wx.requests.length, 0)
})

test('长期不确定的重试仍复用原键，直到明确成功才换新键', async () => {
  const wx = createWx(USER_A)
  const walletApi = loadWalletApi(wx)
  const elevenMinutes = 11 * 60 * 1000
  const oneDay = 24 * 60 * 60 * 1000
  wx.storage.wallet_recharge_pending_11 = {
    '10000|wechat': { key: 'old-key-11min', createdAt: Date.now() - elevenMinutes },
    '20000|wechat': { key: 'old-key-1day', createdAt: Date.now() - oneDay }
  }
  wx.onRequest = respondNetworkFail
  await assert.rejects(walletApi.recharge(100, 'wechat'))
  await assert.rejects(walletApi.recharge(200, 'wechat'))
  assert.strictEqual(keyOf(wx.requests[0]), 'old-key-11min')
  assert.strictEqual(keyOf(wx.requests[1]), 'old-key-1day')

  wx.onRequest = (options) => respondOk(options)
  await walletApi.recharge(100, 'wechat')
  assert.strictEqual(keyOf(wx.requests[2]), 'old-key-11min')
  wx.onRequest = (options) => respondOk(options)
  await walletApi.recharge(100, 'wechat')
  assert.notStrictEqual(keyOf(wx.requests[3]), 'old-key-11min')
  assert.ok(wx.storage.wallet_recharge_pending_11['20000|wechat'], '其他金额的待确认键不受影响')
})

test('读取待确认记录失败时拒绝充值且不发请求', async () => {
  const wx = createWx(USER_A)
  const walletApi = loadWalletApi(wx)
  wx.failures.get = true
  wx.onRequest = (options) => respondOk(options)
  await assert.rejects(walletApi.recharge(100, 'wechat'), /本地存储不可用/)
  assert.strictEqual(wx.requests.length, 0)
})

test('待确认记录数据损坏时拒绝充值且不发请求', async () => {
  const wx = createWx(USER_A)
  const walletApi = loadWalletApi(wx)
  wx.storage.wallet_recharge_pending_11 = 'corrupted'
  wx.onRequest = (options) => respondOk(options)
  await assert.rejects(walletApi.recharge(100, 'wechat'), /本地存储不可用/)
  assert.strictEqual(wx.requests.length, 0)
})

test('写入新键失败或未真正落盘时拒绝充值且不发请求，也不占用 inflight', async () => {
  const wx = createWx(USER_A)
  const walletApi = loadWalletApi(wx)
  wx.onRequest = (options) => respondOk(options)

  wx.failures.set = true
  await assert.rejects(walletApi.recharge(100, 'wechat'), /本地存储不可用/)
  wx.failures.set = false
  wx.failures.silentSet = true
  await assert.rejects(walletApi.recharge(100, 'wechat'), /本地存储不可用/)
  assert.strictEqual(wx.requests.length, 0)

  wx.failures.silentSet = false
  await walletApi.recharge(100, 'wechat')
  assert.strictEqual(wx.requests.length, 1)
})

test('页面在存储失败时提示失败原因且不发充值请求', async () => {
  const wx = createWx(USER_A)
  const { page, toast } = loadRechargePage(wx)
  wx.failures.set = true
  wx.onRequest = (options) => respondOk(options)
  await page.doRecharge()
  assert.strictEqual(wx.requests.filter((o) => o.url.endsWith('/api/wallet/recharge')).length, 0)
  assert.ok(toast.messages.some((message) => /本地存储不可用/.test(message)))
  assert.strictEqual(page.data.submitting, false)
})

test('明确成功后清理失败保留成功标记，新充值和页面重建都使用新键', async () => {
  const wx = createWx(USER_A)
  const walletApi = loadWalletApi(wx)
  wx.onRequest = (options) => respondOk(options, 300)
  wx.failures.remove = true
  const result = await walletApi.recharge(100, 'wechat')
  assert.strictEqual(result, 300)
  assert.strictEqual(wx.requests.length, 1)
  const originalKey = keyOf(wx.requests[0])
  assert.strictEqual(wx.storage.wallet_recharge_pending_11['10000|wechat'].key, originalKey)
  assert.strictEqual(wx.storage.wallet_recharge_pending_11['10000|wechat'].confirmed, true)

  const rebuiltApi = loadWalletApi(wx)
  await rebuiltApi.recharge(100, 'wechat')
  assert.notStrictEqual(keyOf(wx.requests[1]), originalKey, '成功标记必须跨模块重建生效')

  const different = await walletApi.recharge(200, 'wechat')
  assert.strictEqual(different, 300, '清理失败后 inflight 不应卡住，其他金额可继续充值')
  assert.notStrictEqual(keyOf(wx.requests[2]), originalKey)
  const second = await walletApi.recharge(100, 'wechat')
  assert.strictEqual(second, 300)
  assert.notStrictEqual(keyOf(wx.requests[wx.requests.length - 1]), originalKey)
})

test('页面在清理失败时仍提示充值成功且不重发', async () => {
  const wx = createWx(USER_A)
  wx.failures.remove = true
  wx.onRequest = (options) => {
    if (options.url.endsWith('/api/wallet/recharge')) respondOk(options, 300)
    else options.success({ statusCode: 200, data: { code: 200, data: { records: [] } } })
  }
  const { page, toast } = loadRechargePage(wx)
  await page.doRecharge()
  await settle()
  assert.strictEqual(wx.requests.filter((o) => o.url.endsWith('/api/wallet/recharge')).length, 1)
  assert.ok(toast.messages.includes('充值成功'))
  assert.ok(!toast.messages.includes('充值失败'))
  assert.ok(!toast.messages.some((message) => /本地存储不可用/.test(message)))
  assert.strictEqual(page.data.submitting, false)
})

test('成功后的全部存储写操作失败时，本进程仍不会将新充值当成旧键重放', async () => {
  const wx = createWx(USER_A)
  const walletApi = loadWalletApi(wx)
  wx.onRequest = (options) => {
    wx.failures.set = true
    wx.failures.remove = true
    respondOk(options, 200)
  }
  assert.strictEqual(await walletApi.recharge(100, 'wechat'), 200)
  const firstKey = keyOf(wx.requests[0])
  wx.failures.set = false
  wx.failures.remove = false
  wx.onRequest = (options) => respondOk(options, 300)
  assert.strictEqual(await walletApi.recharge(100, 'wechat'), 300)
  assert.notStrictEqual(keyOf(wx.requests[1]), firstKey)
})

test('成功标记未能持久化后重建页面，只确认旧充值，随后主动充值才新增入账', async () => {
  const wx = createWx(USER_A)
  const creditedKeys = new Set()
  let credits = 0
  let breakStorage = true
  wx.onRequest = (options) => {
    if (!options.url.endsWith('/api/wallet/recharge')) {
      respondOk(options, { records: [] })
      return
    }
    const key = keyOf(options)
    if (!creditedKeys.has(key)) { creditedKeys.add(key); credits += 1 }
    if (breakStorage) { wx.failures.set = true; wx.failures.remove = true }
    respondOk(options, 100 + credits * 100)
  }
  await loadWalletApi(wx).recharge(100, 'wechat')
  const firstKey = keyOf(wx.requests[0])
  breakStorage = false
  wx.failures.set = false
  wx.failures.remove = false
  const rebuilt = loadRechargePage(wx)
  await rebuilt.page.doRecharge()
  assert.strictEqual(credits, 1)
  assert.strictEqual(keyOf(wx.requests.filter((o) => o.url.endsWith('/api/wallet/recharge'))[1]), firstKey)
  assert.ok(rebuilt.toast.messages.includes('上次充值已确认'))
  await rebuilt.page.doRecharge()
  assert.strictEqual(credits, 2)
  assert.ok(rebuilt.toast.messages.includes('充值成功'))
})

test('静默删除失败也必须保存成功标记，重建模块后的新充值使用新键', async () => {
  const wx = createWx(USER_A)
  wx.failures.silentRemove = true
  wx.onRequest = (options) => respondOk(options)
  await loadWalletApi(wx).recharge(100, 'wechat')
  const firstKey = keyOf(wx.requests[0])
  assert.strictEqual(wx.storage.wallet_recharge_pending_11['10000|wechat'].confirmed, true)
  await loadWalletApi(wx).recharge(100, 'wechat')
  assert.notStrictEqual(keyOf(wx.requests[1]), firstKey)
})

test('多条待确认并存时，成功清理的静默写失败不能让新充值复用已完成键', async () => {
  const wx = createWx(USER_A)
  const walletApi = loadWalletApi(wx)
  wx.onRequest = respondNetworkFail
  await assert.rejects(walletApi.recharge(100, 'wechat'))
  await assert.rejects(walletApi.recharge(200, 'wechat'))
  const firstKey = keyOf(wx.requests[0])
  const otherKey = keyOf(wx.requests[1])
  wx.onRequest = (options) => {
    wx.failures.silentSet = true
    respondOk(options, 200)
  }
  assert.strictEqual(await walletApi.recharge(100, 'wechat'), 200)
  assert.strictEqual(keyOf(wx.requests[2]), firstKey, '该次请求安全确认原操作')
  await assert.rejects(walletApi.recharge(100, 'wechat'), /本地存储不可用/)
  assert.strictEqual(wx.requests.length, 3, '无法保存新键时不能回放已完成键来冒充新充值')
  wx.failures.silentSet = false
  wx.onRequest = (options) => respondOk(options, 300)
  await walletApi.recharge(100, 'wechat')
  assert.notStrictEqual(keyOf(wx.requests[3]), firstKey)
  assert.strictEqual(wx.storage.wallet_recharge_pending_11['20000|wechat'].key, otherKey)
  assert.strictEqual(wx.storage.wallet_recharge_pending_11['10000|wechat'], undefined)
})

test('金额超过两位小数时拒绝且不发请求，发送标准两位金额', async () => {
  const wx = createWx(USER_A)
  const walletApi = loadWalletApi(wx)
  wx.onRequest = respondNetworkFail
  await assert.rejects(walletApi.recharge(1.005, 'wechat'), /有效充值金额/)
  await assert.rejects(walletApi.recharge(0.001, 'wechat'), /有效充值金额/)
  await assert.rejects(walletApi.recharge('abc', 'wechat'), /有效充值金额/)
  assert.strictEqual(wx.requests.length, 0)

  await assert.rejects(walletApi.recharge(0.1 + 0.2, 'wechat'))
  await assert.rejects(walletApi.recharge('0.30', 'wechat'))
  assert.strictEqual(wx.requests.length, 2)
  assert.strictEqual(wx.requests[0].data.amount, 0.3)
  assert.strictEqual(wx.requests[1].data.amount, 0.3)
  assert.strictEqual(keyOf(wx.requests[0]), keyOf(wx.requests[1]))

  await assert.rejects(walletApi.recharge(0.31, 'wechat'))
  assert.notStrictEqual(keyOf(wx.requests[2]), keyOf(wx.requests[0]))
})
