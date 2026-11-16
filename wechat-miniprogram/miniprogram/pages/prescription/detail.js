const api = require('../../utils/api')
const auth = require('../../utils/auth')
const { formatFee } = require('../../utils/format')
const {
  PAY_METHODS,
  mockChannelPay,
  needsPayDrawer,
  resolvePayMode,
  payNative,
  loadPayMode,
  decoratePayMethods,
  payTipFor
} = require('../../utils/pay')
const toast = require('../../utils/toast')

Page({
  data: {
    prescription: null,
    items: [],
    amountText: '',
    canPay: false,
    balance: '0.00',
    payMethods: PAY_METHODS,
    payMethod: 'wechat',
    payMode: '',
    payTip: '',
    payDrawerVisible: false,
    payDrawerMethod: 'wechat',
    paying: false
  },

  onLoad(options) {
    this.appointmentId = options.appointmentId
    this.prescriptionId = options.id
    this.loadData()
    this.loadBalance()
    this.loadPayMode()
  },

  onUnload() {
    this._disposed = true
    clearTimeout(this._payNavTimer)
    this._payNavTimer = null
  },

  // 读取微信 Native 配置，仅用于隐藏扫码模式下不可用的渠道和展示提示；失败时付款会重新读取并报错
  async loadPayMode() {
    const payMode = await loadPayMode()
    if (this._disposed) return
    if (payMode && payMode !== this.data.payMode) {
      this.setData({ payMode })
      this.syncPayMethods()
    }
  },

  syncPayMethods() {
    const { payMode, balance } = this.data
    // 保留用户已选渠道，NATIVE 下支付宝仍可选（教学模拟）
    const { payMethod } = this.data
    this.setData({
      payMethods: decoratePayMethods(PAY_METHODS, { mode: payMode, balance: this.balanceLoaded ? balance : null }),
      payTip: payTipFor(payMethod, payMode)
    })
  },

  // 读取余额；失败时 balanceLoaded 置为 false，余额支付只提示重读，不判断是否足够
  loadBalance() {
    if (!auth.isLoggedIn()) return Promise.resolve(false)
    this._balanceRequestId = (this._balanceRequestId || 0) + 1
    const requestId = this._balanceRequestId
    return auth.fetchMe().then((user) => {
      if (this._disposed || requestId !== this._balanceRequestId) return !!this.balanceLoaded
      const balance = user.balance != null ? Number(user.balance).toFixed(2) : '0.00'
      this.balanceLoaded = true
      this.setData({ balance })
      this.syncPayMethods()
      return true
    }).catch(() => {
      if (this._disposed || requestId !== this._balanceRequestId) return !!this.balanceLoaded
      this.balanceLoaded = false
      this.syncPayMethods()
      return false
    })
  },

  // 只采用最新一次请求的结果；options.afterPay 表示付款成功后的刷新，失败时仍要告知用户
  loadData(options = {}) {
    this._dataRequestId = (this._dataRequestId || 0) + 1
    const requestId = this._dataRequestId
    const isStale = () => this._disposed || requestId !== this._dataRequestId
    const loader = this.prescriptionId
      ? api.getPrescription(this.prescriptionId)
      : api.getPrescriptionByAppointment(this.appointmentId)
    return loader
      .then((rx) => {
        if (isStale()) return
        if (!rx) {
          toast('暂无处方')
          return
        }
        const list = (rx.items || []).map((item) => ({
          label: `${item.medicineName} ${item.spec || ''}`,
          value: `${item.dosage || ''} ${item.frequency || ''} ×${item.quantity}  ¥${item.amount}`
        }))
        this.setData({
          prescription: rx,
          items: list,
          amountText: formatFee(rx.totalAmount),
          canPay: rx.status === 'unpaid'
        })
      })
      .catch((err) => {
        if (isStale()) return
        toast(options.afterPay ? '支付成功，处方状态刷新失败，请稍后重新进入查看' : (err.message || '加载失败'))
      })
  },

  onPayMethodTap(e) {
    const { id } = e.currentTarget.dataset
    if (!id) return
    this.setData({ payMethod: id, payTip: payTipFor(id, this.data.payMode) })
    if (needsPayDrawer(id)) {
      this.setData({ payDrawerVisible: true, payDrawerMethod: id })
    }
  },

  onPayDrawerClose() {
    this.setData({ payDrawerVisible: false })
  },

  onPayDrawerConfirm() {
    this.doPay()
  },

  onPay() {
    if (!this.data.canPay || this.data.paying) return
    const { payMethod } = this.data
    if (needsPayDrawer(payMethod)) {
      this.setData({ payDrawerVisible: true, payDrawerMethod: payMethod })
      return
    }
    this.doPay()
  },

  async doPay() {
    const { prescription, payMethod, balance } = this.data
    if (!prescription || this.data.paying) return
    if (payMethod === 'balance') {
      if (!this.balanceLoaded) {
        toast('余额读取失败，正在重试，请稍后再点击支付')
        this.loadBalance().then((ok) => {
          if (!ok && !this._disposed) toast('余额仍无法读取，请检查网络后重试')
        })
        return
      }
      if (Number(prescription.totalAmount) > Number(balance)) {
        toast('余额不足，请先充值')
        return
      }
    }
    this.setData({ paying: true })
    let paid = false
    try {
      const mode = await resolvePayMode(payMethod)
      if (mode === 'NATIVE') {
        await payNative({ businessType: 'PRESCRIPTION', businessId: prescription.id })
      } else {
        await mockChannelPay(payMethod)
        await api.payPrescription(prescription.id, payMethod)
      }
      paid = true
      try {
        await auth.fetchMe()
      } catch (e) {
        // 支付已成功，仅余额刷新失败，不能提示支付失败
      }
      this.setData({ payDrawerVisible: false })
      wx.showToast({ title: '支付成功，请等待取药', icon: 'success' })
      if (this._disposed) return
      this.loadData({ afterPay: true })
      this.loadBalance()
      clearTimeout(this._payNavTimer)
      this._payNavTimer = setTimeout(() => {
        this._payNavTimer = null
        if (this._disposed) return
        const pages = getCurrentPages()
        if (pages.length > 1) wx.navigateBack()
      }, 800)
    } catch (err) {
      if (paid) return
      toast(err.message || '支付失败')
      if (err && (err.code === 'PAYMENT_PENDING' || err.code === 'PAYMENT_REVIEW')) {
        // 结果待确认或待人工处理时关闭抽屉并刷新处方，避免重复付款
        this.setData({ payDrawerVisible: false })
        if (!this._disposed) this.loadData()
      }
    } finally {
      this.setData({ paying: false })
    }
  }
})
