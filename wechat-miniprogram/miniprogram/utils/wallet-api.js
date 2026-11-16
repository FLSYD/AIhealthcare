const { request } = require('./request')
const nativePay = require('./native-pay')

const USER_KEY = 'user'
const PENDING_KEY_PREFIX = 'wallet_recharge_pending_'
const STORAGE_ERROR_MESSAGE = '本地存储不可用，无法安全充值，请稍后重试'

const inflight = {}
// 仅记录已收到成功响应的操作，避免清理故障导致本进程的新充值误复用旧键。
const confirmedKeys = {}
let keySeq = 0

function pendingStorageKey(userId) {
  return PENDING_KEY_PREFIX + userId
}

// 直接读取登录用户缓存，避免对 auth 模块新增依赖
function currentUserId() {
  try {
    const user = wx.getStorageSync(USER_KEY)
    return user && user.id != null ? String(user.id) : ''
  } catch (e) {
    return ''
  }
}

// 读取某用户的未确认充值记录：以“金额分|渠道”为指纹。
// 服务端可能已入账而响应丢失，所以记录不设过期，直到明确成功才清除；
// 存储读取异常或数据损坏时直接抛错，调用方据此拒绝充值，不能在失去记录的情况下换新键
function readPending(userId) {
  const result = {}
  let stored
  try {
    stored = wx.getStorageSync(pendingStorageKey(userId))
  } catch (e) {
    throw new Error(STORAGE_ERROR_MESSAGE)
  }
  if (stored === '' || stored == null) return result
  if (typeof stored !== 'object' || Array.isArray(stored)) throw new Error(STORAGE_ERROR_MESSAGE)
  Object.keys(stored).forEach((fingerprint) => {
    const entry = stored[fingerprint]
    if (!entry || typeof entry.key !== 'string' || !entry.key) throw new Error(STORAGE_ERROR_MESSAGE)
    result[fingerprint] = entry
  })
  return result
}

// 写入后回读校验，写入失败或未真正落盘都抛错
function writePending(userId, pending) {
  const storageKey = pendingStorageKey(userId)
  const fingerprints = Object.keys(pending)
  try {
    if (fingerprints.length) {
      wx.setStorageSync(storageKey, pending)
      const saved = wx.getStorageSync(storageKey)
      if (!saved || typeof saved !== 'object' || Array.isArray(saved)
        || Object.keys(saved).length !== fingerprints.length) throw new Error('verify keys')
      const failed = fingerprints.some((fingerprint) => (
        !saved || !saved[fingerprint] || saved[fingerprint].key !== pending[fingerprint].key
        || saved[fingerprint].confirmed !== pending[fingerprint].confirmed
      ))
      if (failed) throw new Error('verify')
    } else {
      wx.removeStorageSync(storageKey)
      const saved = wx.getStorageSync(storageKey)
      if (saved && Object.keys(saved).length) throw new Error('verify removal')
    }
  } catch (e) {
    throw new Error(STORAGE_ERROR_MESSAGE)
  }
}

function createKey() {
  keySeq += 1
  const random = Math.random().toString(36).slice(2, 10)
  return `rc-${Date.now().toString(36)}-${random}-${keySeq}`
}

// 同一用户、同一金额与渠道的未确认尝试复用旧键；新键必须先持久化成功才返回
function acquireKey(userId, fingerprint) {
  const pending = readPending(userId)
  const previous = pending[fingerprint]
  const confirmed = previous && (previous.confirmed === true
    || confirmedKeys[`${userId}|${fingerprint}`] === previous.key)
  if (previous && !confirmed) return { key: previous.key, recovering: true }
  pending[fingerprint] = { key: createKey(), createdAt: Date.now() }
  writePending(userId, pending)
  delete confirmedKeys[`${userId}|${fingerprint}`]
  return { key: pending[fingerprint].key, recovering: false }
}

// 仅用于明确成功后的清理，调用方需自行吞掉异常
function forgetKey(userId, fingerprint, key) {
  const confirmedId = `${userId}|${fingerprint}`
  confirmedKeys[confirmedId] = key
  const pending = readPending(userId)
  if (pending[fingerprint] && pending[fingerprint].key === key) {
    const entry = pending[fingerprint]
    delete pending[fingerprint]
    try {
      writePending(userId, pending)
      delete confirmedKeys[confirmedId]
    } catch (e) {
      // 删除失败时保存成功标记，页面重建后新充值也能换键。
      pending[fingerprint] = { ...entry, confirmed: true }
      writePending(userId, pending)
    }
  }
}

// 金额必须是最多两位小数的正数，返回分；否则返回 0
function toCents(amount) {
  const value = Number(amount)
  if (!Number.isFinite(value) || value <= 0) return 0
  const cents = Math.round(value * 100)
  if (Math.abs(value * 100 - cents) > 1e-6) return 0
  return cents
}

function recharge(amount, payMethod, options = {}) {
  const userId = currentUserId()
  if (!userId) return Promise.reject(new Error('请先登录'))
  const cents = toCents(amount)
  if (!cents) {
    return Promise.reject(new Error('请输入有效充值金额'))
  }
  const standardAmount = cents / 100
  if (payMethod !== 'wechat' && payMethod !== 'alipay') {
    const error = new Error('不支持的充值方式')
    error.code = 'PAYMENT_CHANNEL_UNAVAILABLE'
    return Promise.reject(error)
  }
  const fingerprint = `${cents}|${payMethod}`
  const active = inflight[userId]
  if (active) {
    if (active.fingerprint === fingerprint) return active.promise
    return Promise.reject(new Error('已有一笔充值正在处理，请稍后再试'))
  }
  let key = ''
  // 先读取支付配置再取幂等键；仅微信在扫码启用时走 Native，支付宝仍走旧充值接口（教学模拟），幂等逻辑一致
  const promise = nativePay.getConfig().then((config) => {
    const useNative = config.enabled === true && payMethod === 'wechat'
    const operation = acquireKey(userId, fingerprint)
    key = operation.key
    if (operation.recovering && typeof options.onRecover === 'function') options.onRecover()
    if (useNative) {
      return nativePay.pay({ businessType: 'RECHARGE', amount: standardAmount }, { idempotencyKey: key })
    }
    return request({
      url: '/api/wallet/recharge',
      method: 'POST',
      data: { amount: standardAmount, payMethod },
      header: { 'Idempotency-Key': key }
    })
  })
  const tracked = promise.then(
    (data) => {
      delete inflight[userId]
      // 仅在服务端明确成功后结束该次尝试，后续新充值使用新键；
      // 清理失败时保留原键，不能反过来报充值失败
      try {
        forgetKey(userId, fingerprint, key)
      } catch (e) {
        // 记录留待后续同参数请求安全确认
      }
      return data
    },
    (error) => {
      delete inflight[userId]
      // 仅 Native 明确关闭或失败（后端确认不会收款）才结束该次尝试；
      // 待确认、待人工处理、网络错误一律保留原键
      if (key && error && (error.code === 'PAYMENT_CLOSED' || error.code === 'PAYMENT_FAILED')) {
        try {
          forgetKey(userId, fingerprint, key)
        } catch (e) {
          // 记录留待后续同参数请求安全确认
        }
      }
      throw error
    }
  )
  inflight[userId] = { fingerprint, promise: tracked }
  return tracked
}

function fetchBalanceLogs(params = {}) {
  const data = {
    current: params.current || 1,
    size: params.size || 20
  }
  // 可选类型筛选（如 recharge），由后端在分页前过滤
  if (params.type) data.type = params.type
  return request({ url: '/api/wallet/logs', data })
}

module.exports = {
  recharge,
  fetchBalanceLogs
}
