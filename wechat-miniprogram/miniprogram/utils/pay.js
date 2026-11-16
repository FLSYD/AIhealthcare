const nativePay = require('./native-pay')

const PAY_METHODS = [
  { id: 'wechat', name: '微信支付', desc: '', icon: 'wechat', logo: '' },
  { id: 'alipay', name: '支付宝（模拟）', desc: '仅教学模拟，不会真实扣款', icon: 'alipay', logo: '' },
  { id: 'balance', name: '余额支付', desc: '', icon: 'balance', logo: '' }
]

const PAY_CHANNEL_INFO = {
  wechat: { name: '微信支付', logo: '' },
  alipay: { name: '支付宝（模拟）', logo: '' },
  balance: { name: '余额支付', logo: '' }
}

const ALIPAY_SIMULATION_TIP = '支付宝为仅教学模拟，不会真实扣款。'

function payMethodLabel(method) {
  const map = {
    balance: '余额支付',
    wechat: '微信支付',
    alipay: '支付宝（模拟）'
  }
  return map[method] || method || ''
}

function getPayChannelInfo(method) {
  return PAY_CHANNEL_INFO[method] || PAY_CHANNEL_INFO.wechat
}

function isChannelPay(method) {
  return method === 'wechat' || method === 'alipay'
}

function needsPayDrawer(method) {
  return method === 'wechat' || method === 'alipay' || method === 'balance'
}

function mockChannelPay(payMethod) {
  return new Promise((resolve) => {
    if (payMethod === 'balance') {
      resolve()
      return
    }
    setTimeout(() => resolve(), 800)
  })
}

// 决定本次付款走微信扫码(NATIVE)还是原有流程(LEGACY)。
// 配置读取失败直接抛错，不降级；扫码启用时仅微信走 NATIVE，支付宝明确走旧模拟流程，未知渠道直接拒绝。
function resolvePayMode(payMethod) {
  if (payMethod === 'balance') return Promise.resolve('LEGACY')
  if (payMethod !== 'wechat' && payMethod !== 'alipay') {
    const error = new Error('不支持的支付方式')
    error.code = 'PAYMENT_CHANNEL_UNAVAILABLE'
    return Promise.reject(error)
  }
  return nativePay.getConfig().then((config) => {
    if (config.enabled && payMethod === 'wechat') return 'NATIVE'
    return 'LEGACY'
  })
}

function payNative(params, options) {
  return nativePay.pay(params, options)
}

// 仅用于界面展示；读取失败返回空串，真实付款时仍会重新读取配置并报错
function loadPayMode() {
  return nativePay.getConfig().then(
    (config) => (config.enabled ? 'NATIVE' : 'MOCK'),
    () => ''
  )
}

// 按模式生成支付方式列表：支付宝始终保留并标注模拟，余额显示可用额度
function decoratePayMethods(methods, options = {}) {
  const mode = options.mode || ''
  const balance = options.balance
  return methods.map((item) => {
    let desc = item.desc
    if (item.id === 'alipay') desc = '仅教学模拟，不会真实扣款'
    else if (item.id === 'balance') desc = balance != null ? `可用 ¥${balance}` : ''
    else if (mode === 'NATIVE' && item.id === 'wechat') desc = '真实支付，需另一台设备扫码'
    else if (mode === 'MOCK') desc = '模拟支付'
    return { ...item, name: item.id === 'alipay' ? '支付宝（模拟）' : item.name, desc }
  })
}

function payTipFor(method, mode) {
  if (mode === 'NATIVE' && method === 'wechat') {
    return '微信真实支付，将真实扣款。确认后显示二维码，请用另一台设备的微信“扫一扫”，不能长按或从相册识别。'
  }
  if (method === 'alipay') return ALIPAY_SIMULATION_TIP
  return ''
}

module.exports = {
  PAY_METHODS,
  PAY_CHANNEL_INFO,
  payMethodLabel,
  getPayChannelInfo,
  isChannelPay,
  needsPayDrawer,
  mockChannelPay,
  resolvePayMode,
  payNative,
  loadPayMode,
  decoratePayMethods,
  payTipFor
}
