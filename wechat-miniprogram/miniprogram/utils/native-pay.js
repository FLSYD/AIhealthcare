const { request } = require('./request')

// 微信 Native 扫码支付封装：配置缓存、支付单创建/查询/关闭、扫码页桥接
const CONFIG_TTL = 60 * 1000
const POLL_INTERVAL = 3000
const TERMINAL_STATUSES = ['SUCCEEDED', 'CLOSED', 'FAILED', 'NEEDS_REVIEW']
const CODE_CLOSED = 'PAYMENT_CLOSED'
const CODE_FAILED = 'PAYMENT_FAILED'
const CODE_REVIEW = 'PAYMENT_REVIEW'
const CODE_PENDING = 'PAYMENT_PENDING'
const REVIEW_MESSAGE = '已支付待人工处理，请勿重复支付'
const PENDING_MESSAGE = '支付结果待确认，请勿重复支付'

let configCache = null
let configExpiry = 0
let configLoading = null

function isTerminal(status) {
  return TERMINAL_STATUSES.indexOf(status) !== -1
}

// 仅缓存成功的配置；失败必须原样抛出，调用方不得降级为模拟支付
function getConfig() {
  if (configCache && Date.now() < configExpiry) return Promise.resolve(configCache)
  if (configLoading) return configLoading
  configLoading = request({ url: '/api/payments/wechat/native/config' })
    .then((data) => {
      if (!data || typeof data.enabled !== 'boolean') throw new Error('微信支付配置异常，请稍后重试')
      configCache = { enabled: data.enabled, mode: data.mode }
      configExpiry = Date.now() + CONFIG_TTL
      return configCache
    })
    .finally(() => {
      configLoading = null
    })
  return configLoading
}

function resetConfigCache() {
  configCache = null
  configExpiry = 0
  configLoading = null
}

function normalizeView(view) {
  if (!view || typeof view !== 'object' || view.paymentId == null || typeof view.status !== 'string') {
    throw new Error('支付单响应异常，请稍后重试')
  }
  return view
}

function createPayment(params, options = {}) {
  const header = {}
  if (options.idempotencyKey) header['Idempotency-Key'] = options.idempotencyKey
  return request({ url: '/api/payments/wechat/native', method: 'POST', data: params, header }).then(normalizeView)
}

function queryPayment(paymentId) {
  return request({ url: `/api/payments/wechat/native/${encodeURIComponent(paymentId)}` }).then(normalizeView)
}

function closePayment(paymentId) {
  return request({ url: `/api/payments/wechat/native/${encodeURIComponent(paymentId)}/close`, method: 'POST' }).then(normalizeView)
}

// 将非成功的支付单状态转换为带 code 的错误；只有 CLOSED/FAILED 表示确认不会收款
function buildError(view) {
  let code = CODE_PENDING
  let message = PENDING_MESSAGE
  if (view.status === 'CLOSED') {
    code = CODE_CLOSED
    message = '支付已关闭'
  } else if (view.status === 'FAILED') {
    code = CODE_FAILED
    message = '支付失败'
  } else if (view.status === 'NEEDS_REVIEW') {
    code = CODE_REVIEW
    message = REVIEW_MESSAGE
  }
  const error = new Error(message)
  error.code = code
  error.payment = view
  return error
}

// 打开扫码页并等待页面卸载时回传的最后一次支付单状态
function openNativePage(view) {
  return new Promise((resolve, reject) => {
    wx.navigateTo({
      url: `/pages/payment/native?paymentId=${encodeURIComponent(view.paymentId)}`,
      events: {
        finish(payload) {
          const latest = payload && payload.payment
          resolve(latest && latest.paymentId != null && typeof latest.status === 'string' ? latest : view)
        }
      },
      success(res) {
        res.eventChannel.emit('init', { payment: view })
      },
      fail() {
        const error = new Error('无法打开扫码页，支付单已保留')
        error.code = CODE_PENDING
        error.payment = view
        reject(error)
      }
    })
  })
}

// 创建支付单并展示扫码页；仅 SUCCEEDED 才 resolve，其余状态按 code 拒绝
async function pay(params, options = {}) {
  const created = await createPayment(params, options)
  if (created.status === 'SUCCEEDED') return created
  if (isTerminal(created.status)) throw buildError(created)
  let latest = await openNativePage(created)
  if (!isTerminal(latest.status)) {
    try {
      latest = await queryPayment(created.paymentId)
    } catch (e) {
      // 查单失败时保持待确认
    }
  }
  if (latest.status === 'SUCCEEDED') return latest
  throw buildError(latest)
}

module.exports = {
  POLL_INTERVAL,
  TERMINAL_STATUSES,
  CODE_CLOSED,
  CODE_FAILED,
  CODE_REVIEW,
  CODE_PENDING,
  REVIEW_MESSAGE,
  PENDING_MESSAGE,
  isTerminal,
  getConfig,
  resetConfigCache,
  createPayment,
  queryPayment,
  closePayment,
  buildError,
  pay
}
