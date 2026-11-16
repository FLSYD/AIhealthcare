const auth = require('../../utils/auth')
const walletApi = require('../../utils/wallet-api')
const {
  PAY_METHODS,
  mockChannelPay,
  resolvePayMode,
  loadPayMode,
  decoratePayMethods,
  payTipFor
} = require('../../utils/pay')
const toast = require('../../utils/toast')
const { formatServerDateTime } = require('../../utils/format')

const LOG_PAGE_SIZE = 10
const RECHARGE_PAY_METHODS = PAY_METHODS.filter((item) => item.id === 'wechat' || item.id === 'alipay')

function formatBalance(user) {
  if (!user || user.balance == null) return '0.00'
  return Number(user.balance).toFixed(2)
}

Page({
  data: {
    balanceText: '0.00',
    amountOptions: [50, 100, 200, 500],
    selectedAmount: 100,
    customAmount: '',
    rechargeAmount: '100.00',
    payMethods: RECHARGE_PAY_METHODS,
    payMethod: 'wechat',
    payMode: '',
    payTip: '',
    payDrawerVisible: false,
    payDrawerMethod: 'wechat',
    submitting: false,
    logs: [],
    logsLoading: false,
    logsLoadingMore: false,
    logsHasMore: false,
    logsError: false
  },

  onShow() {
    if (!auth.isLoggedIn()) {
      toast('请先登录')
      clearTimeout(this._loginRedirectTimer)
      this._loginRedirectTimer = setTimeout(() => {
        this._loginRedirectTimer = null
        if (this._disposed) return
        wx.switchTab({ url: '/pages/mine/mine' })
      }, 500)
      return
    }
    clearTimeout(this._loginRedirectTimer)
    this._loginRedirectTimer = null
    this.refreshBalance()
    this.loadLogs()
    this.loadPayMode()
  },

  onHide() {
    clearTimeout(this._loginRedirectTimer)
    this._loginRedirectTimer = null
  },

  onUnload() {
    this._disposed = true
    clearTimeout(this._loginRedirectTimer)
    this._loginRedirectTimer = null
  },

  // 读取微信 Native 配置以展示渠道提示；失败时充值会重新读取并报错
  async loadPayMode() {
    const payMode = await loadPayMode()
    if (!payMode || payMode === this.data.payMode) return
    const { payMethod } = this.data
    this.setData({
      payMode,
      payMethods: decoratePayMethods(RECHARGE_PAY_METHODS, { mode: payMode }),
      payTip: payTipFor(payMethod, payMode)
    })
  },

  async refreshBalance() {
    try {
      const user = await auth.fetchMe()
      this.setData({ balanceText: formatBalance(user) })
    } catch (e) {
      toast(e.message || '加载余额失败')
    }
  },

  formatLog(item) {
    return {
      ...item,
      // 支付宝充值为教学模拟，记录中明确标注，区别于微信真实充值
      typeText: item.payMethod === 'alipay' && !/模拟/.test(item.typeText || '')
        ? `${item.typeText || '充值'}（支付宝模拟）`
        : item.typeText,
      amountText: Number(Math.abs(item.amount)).toFixed(2),
      createTimeText: formatServerDateTime(item.createTime)
    }
  },

  // 重新加载第一页，type=recharge 由服务端在分页前筛选
  loadLogs() {
    this._logsSeq = (this._logsSeq || 0) + 1
    this._logsCurrent = 0
    this.setData({ logs: [], logsLoading: true, logsLoadingMore: false, logsHasMore: false, logsError: false })
    return this.fetchLogsPage(this._logsSeq)
  },

  async fetchLogsPage(seq) {
    const page = (this._logsCurrent || 0) + 1
    try {
      const result = await walletApi.fetchBalanceLogs({ current: page, size: LOG_PAGE_SIZE, type: 'recharge' })
      if (seq !== this._logsSeq) return
      const records = (result && result.records) || []
      const total = Number(result && result.total) || 0
      this._logsCurrent = page
      const known = {}
      this.data.logs.forEach((log) => { known[log.id] = true })
      const incoming = []
      records.forEach((item) => {
        if (known[item.id]) return
        known[item.id] = true
        incoming.push(this.formatLog(item))
      })
      this.setData({
        logs: this.data.logs.concat(incoming),
        logsHasMore: records.length >= LOG_PAGE_SIZE && (!total || page * LOG_PAGE_SIZE < total),
        logsError: false
      })
    } catch (e) {
      if (seq !== this._logsSeq) return
      this.setData({ logsError: true })
    } finally {
      if (seq === this._logsSeq) this.setData({ logsLoading: false, logsLoadingMore: false })
    }
  },

  onLogsMore() {
    const { logsHasMore, logsLoading, logsLoadingMore } = this.data
    if (!logsHasMore || logsLoading || logsLoadingMore) return
    this.setData({ logsLoadingMore: true, logsError: false })
    this.fetchLogsPage(this._logsSeq)
  },

  onLogsRetry() {
    if (this.data.logsLoading || this.data.logsLoadingMore) return
    if (!this.data.logs.length && !this._logsCurrent) {
      this.loadLogs()
      return
    }
    this.onLogsMore()
  },

  // 页面触底时继续加载更多充值记录
  onReachBottom() {
    if (this.data.logsError) return
    this.onLogsMore()
  },
  updateRechargeAmount() {
    const { selectedAmount, customAmount } = this.data
    let amount = selectedAmount
    if (customAmount) {
      const custom = Number(customAmount)
      if (!Number.isNaN(custom) && custom > 0) amount = custom
    }
    this.setData({ rechargeAmount: Number(amount || 0).toFixed(2) })
  },

  onAmountTap(e) {
    const amount = Number(e.currentTarget.dataset.amount)
    if (!amount) return
    this.setData({ selectedAmount: amount, customAmount: '' })
    this.updateRechargeAmount()
  },

  onCustomAmountChange(e) {
    this.setData({ customAmount: (e.detail || '').trim(), selectedAmount: 0 })
    this.updateRechargeAmount()
  },

  onPayMethodTap(e) {
    if (this.rechargeLocked || this.data.submitting) return
    const { id } = e.currentTarget.dataset
    if (!id) return
    this.setData({
      payMethod: id,
      payTip: payTipFor(id, this.data.payMode),
      payDrawerVisible: true,
      payDrawerMethod: id
    })
  },

  onPayDrawerClose() {
    if (this.rechargeLocked || this.data.submitting) return
    this.setData({ payDrawerVisible: false })
  },

  onPayDrawerConfirm() {
    this.doRecharge()
  },

  onRechargeTap() {
    const amount = Number(this.data.rechargeAmount)
    if (!amount || amount < 0.01) {
      toast('请输入有效充值金额')
      return
    }
    this.setData({ payDrawerVisible: true, payDrawerMethod: this.data.payMethod })
  },

  async doRecharge() {
    // 实例标志同步生效，避免 setData 异步导致快速连点发出多次请求
    if (this.rechargeLocked || this.data.submitting) return
    const amount = Number(this.data.rechargeAmount)
    // 固定用户本次确认的渠道，异步等待期间的页面变化不能改变实际付款方式。
    const payMethod = this.data.payMethod
    if (!amount || amount < 0.01) {
      toast('请输入有效充值金额')
      return
    }
    if (amount > 10000) {
      toast('单次充值不能超过 10000 元')
      return
    }
    this.rechargeLocked = true
    this.setData({ submitting: true })
    let confirmed = false
    let recovering = false
    try {
      // 扫码模式由 wallet-api 直接创建 Native 支付单，不再执行模拟渠道等待
      const mode = await resolvePayMode(payMethod)
      if (mode !== 'NATIVE') await mockChannelPay(payMethod)
      await walletApi.recharge(amount, payMethod, { onRecover() { recovering = true } })
      confirmed = true
      this.setData({
        payDrawerVisible: false,
        customAmount: '',
        selectedAmount: 100
      })
      this.updateRechargeAmount()
      toast.success(recovering ? '上次充值已确认' : '充值成功')
      this.loadLogs()
      try {
        const user = await auth.fetchMe()
        this.setData({ balanceText: formatBalance(user) })
      } catch (e) {
        // 充值已被服务端确认，仅余额刷新失败，不能提示充值失败
        toast((recovering ? '上次充值已确认' : '充值成功') + '，余额刷新失败，请稍后下拉刷新')
      }
    } catch (e) {
      if (!confirmed) {
        toast(e.message || '充值失败')
        // 结果待确认或待人工处理时收起抽屉，保留原幂等键，避免重复付款
        if (e && (e.code === 'PAYMENT_PENDING' || e.code === 'PAYMENT_REVIEW')) this.setData({ payDrawerVisible: false })
      }
    } finally {
      this.rechargeLocked = false
      this.setData({ submitting: false })
    }
  }
})
