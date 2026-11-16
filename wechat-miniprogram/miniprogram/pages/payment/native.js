const nativePay = require('../../utils/native-pay')
const { baseUrl } = require('../../utils/config')
const toast = require('../../utils/toast')

const QR_PATH_PREFIX = '/api/payments/wechat/native/cashier/'

// 各支付单状态对应的展示文案；NEEDS_REVIEW 不得出现退款或失败含义
const STATUS_INFO = {
  CREATING: { text: '正在生成支付单', tone: 'pending', detail: '请稍候' },
  PENDING: { text: '等待扫码支付', tone: 'pending', detail: '请在另一台设备的微信中扫码并确认支付' },
  UNKNOWN: { text: '支付结果确认中', tone: 'warning', detail: '正在向微信确认结果，请勿重复支付' },
  CLOSING: { text: '正在关闭支付单', tone: 'warning', detail: '关闭结果确认前请勿重复支付' },
  SUCCEEDED: { text: '支付成功', tone: 'success', detail: '即将返回' },
  CLOSED: { text: '支付已关闭', tone: 'muted', detail: '该支付单已关闭，可返回后重新发起' },
  FAILED: { text: '支付失败', tone: 'danger', detail: '本次支付未成功，可返回后重新发起' },
  NEEDS_REVIEW: {
    text: nativePay.REVIEW_MESSAGE,
    tone: 'warning',
    detail: '请联系医院工作人员核对，请勿再次发起付款'
  }
}

function formatClock(epochMillis) {
  const value = Number(epochMillis)
  if (!Number.isFinite(value) || value <= 0) return ''
  const date = new Date(value)
  const pad = (number) => (number < 10 ? `0${number}` : String(number))
  return `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
}

// 二维码地址只接受后端约定的相对路径，拼接当前 baseUrl 后由 image 加载
function buildQrUrl(codeImageUrl, nonce) {
  if (typeof codeImageUrl !== 'string' || codeImageUrl.indexOf(QR_PATH_PREFIX) !== 0) return ''
  return `${baseUrl}${codeImageUrl}${nonce ? `?t=${nonce}` : ''}`
}

Page({
  data: {
    loading: true,
    loadError: '',
    status: '',
    statusText: '',
    detailText: '',
    tone: 'pending',
    amountText: '0.00',
    expireText: '',
    qrUrl: '',
    qrFailed: false,
    cashierUrl: '',
    showQr: false,
    terminal: false,
    canClose: false,
    closing: false,
    netHint: ''
  },

  onLoad(options) {
    this.paymentId = options.paymentId
    this.latest = null
    this.destroyed = false
    this.finished = false
    this.polling = false
    this.qrNonce = 0
    this.alertEnabled = false
    let channel = null
    try {
      channel = this.getOpenerEventChannel()
    } catch (e) {
      channel = null
    }
    this.channel = channel
    if (channel && typeof channel.on === 'function') {
      channel.on('init', (payload) => {
        if (payload && payload.payment) this.applyView(payload.payment)
        else this.loadFromServer()
      })
    } else {
      this.loadFromServer()
    }
  },

  onUnload() {
    this.destroyed = true
    this.stopPolling()
    if (this.leaveTimer) clearTimeout(this.leaveTimer)
    this.leaveTimer = null
    this.emitFinish()
  },

  // 页面卸载时回传最后一次支付单状态，调用方据此决定成功、失败或待确认
  emitFinish() {
    if (this.finished) return
    this.finished = true
    if (this.channel && typeof this.channel.emit === 'function') {
      this.channel.emit('finish', { payment: this.latest })
    }
  },

  loadFromServer() {
    this.setData({ loading: true, loadError: '' })
    return nativePay.queryPayment(this.paymentId).then(
      (view) => {
        if (!this.destroyed) this.applyView(view)
      },
      (error) => {
        if (!this.destroyed) this.setData({ loading: false, loadError: error.message || '支付信息加载失败' })
      }
    )
  },

  onReload() {
    this.loadFromServer()
  },

  applyView(view) {
    this.latest = view
    const info = STATUS_INFO[view.status] || STATUS_INFO.UNKNOWN
    const terminal = nativePay.isTerminal(view.status)
    const showQr = view.status === 'PENDING'
    this.setData({
      loading: false,
      loadError: '',
      status: view.status,
      statusText: info.text,
      detailText: info.detail,
      tone: info.tone,
      amountText: view.amount != null ? String(view.amount) : '0.00',
      expireText: showQr ? formatClock(view.expiresAt) : '',
      qrUrl: buildQrUrl(view.codeImageUrl, this.qrNonce),
      cashierUrl: view.cashierUrl || '',
      showQr,
      terminal,
      canClose: view.status === 'PENDING' || view.status === 'UNKNOWN',
      netHint: ''
    })
    this.setUnloadAlert(!terminal)
    if (terminal) {
      this.stopPolling()
      if (view.status === 'SUCCEEDED' && !this.leaveTimer) {
        this.leaveTimer = setTimeout(() => this.onLeave(), 1200)
      }
    } else {
      this.schedulePoll()
    }
  },

  setUnloadAlert(enabled) {
    if (this.alertEnabled === enabled) return
    this.alertEnabled = enabled
    try {
      if (enabled && typeof wx.enableAlertBeforeUnload === 'function') {
        wx.enableAlertBeforeUnload({ message: '支付单会保留，结果将显示为待确认。如需放弃请先点“关闭支付”。' })
      } else if (!enabled && typeof wx.disableAlertBeforeUnload === 'function') {
        wx.disableAlertBeforeUnload()
      }
    } catch (e) {
      // 旧基础库不支持返回确认，按普通返回处理
    }
  },

  schedulePoll() {
    if (this.destroyed || this.pollTimer) return
    this.pollTimer = setTimeout(() => {
      this.pollTimer = null
      this.poll()
    }, nativePay.POLL_INTERVAL)
  },

  stopPolling() {
    if (this.pollTimer) clearTimeout(this.pollTimer)
    this.pollTimer = null
  },

  async poll() {
    if (this.destroyed || this.polling) return
    this.polling = true
    try {
      const view = await nativePay.queryPayment(this.paymentId)
      if (!this.destroyed) this.applyView(view)
    } catch (error) {
      if (!this.destroyed) {
        this.setData({ netHint: '网络不稳定，正在重试' })
        this.schedulePoll()
      }
    } finally {
      this.polling = false
    }
  },

  onCopyLink() {
    const { cashierUrl } = this.data
    if (!cashierUrl) {
      toast('暂无可复制的链接')
      return
    }
    wx.setClipboardData({
      data: cashierUrl,
      fail: () => toast('复制失败，请重试')
    })
  },

  onQrError() {
    this.setData({ qrFailed: true })
  },

  onRetryQr() {
    this.qrNonce = Date.now()
    this.setData({ qrFailed: false, qrUrl: buildQrUrl(this.latest && this.latest.codeImageUrl, this.qrNonce) })
  },

  onCloseTap() {
    if (this.data.closing) return
    wx.showModal({
      title: '关闭支付',
      content: '关闭后此二维码失效。确认关闭吗？',
      success: (res) => {
        if (res.confirm) this.doClose()
      }
    })
  },

  async doClose() {
    if (this.data.closing) return
    this.setData({ closing: true })
    try {
      const view = await nativePay.closePayment(this.paymentId)
      if (!this.destroyed) this.applyView(view)
    } catch (error) {
      toast(error.message || '关闭失败，请稍后重试')
    } finally {
      if (!this.destroyed) this.setData({ closing: false })
    }
  },

  onLeave() {
    wx.navigateBack({ delta: 1 })
  }
})
