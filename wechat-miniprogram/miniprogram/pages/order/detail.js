const api = require('../../utils/api')
const auth = require('../../utils/auth')
const { toOrder } = require('../../utils/adapter')
const { formatDateTime, formatFee } = require('../../utils/format')
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
    order: null,
    detailItems: [],
    feeText: '',
    rxFeeText: '',
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
    this.loadOrder(options.id)
  },

  onShow() {
    if (this._disposed) return
    if (this.orderId) this.loadOrder(this.orderId)
    this.loadBalance()
    this.loadPayMode()
  },

  onUnload() {
    this._disposed = true
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
  async loadBalance() {
    if (!auth.isLoggedIn()) return false
    this._balanceRequestId = (this._balanceRequestId || 0) + 1
    const requestId = this._balanceRequestId
    try {
      const user = await auth.fetchMe()
      if (this._disposed || requestId !== this._balanceRequestId) return !!this.balanceLoaded
      const balance = user.balance != null ? Number(user.balance).toFixed(2) : '0.00'
      this.balanceLoaded = true
      this.setData({ balance })
      this.syncPayMethods()
      return true
    } catch (e) {
      if (this._disposed || requestId !== this._balanceRequestId) return !!this.balanceLoaded
      this.balanceLoaded = false
      this.syncPayMethods()
      return false
    }
  },

  // 只采用最新一次请求的结果；options.afterPay 表示付款成功后的刷新，失败时仍要告知用户
  loadOrder(id, options = {}) {
    this.orderId = id
    this._orderRequestId = (this._orderRequestId || 0) + 1
    const requestId = this._orderRequestId
    const isStale = () => this._disposed || requestId !== this._orderRequestId
    return api.getAppointmentById(id)
      .then((appt) => {
        if (isStale()) return
        const order = toOrder(appt)
        const items = [
          { label: '就诊医生', value: order.doctorName },
          { label: '就诊科室', value: order.departmentName },
          { label: '就诊时间', value: formatDateTime(order.scheduleDate, order.timeSlot) },
          { label: '就诊人', value: order.patientName },
          { label: '联系电话', value: order.patientPhone },
          { label: '挂号费用', value: formatFee(order.fee) },
          { label: '订单编号', value: order.orderNo }
        ]
        if (order.payMethodText) {
          items.push({ label: '支付方式', value: order.payMethodText })
        }
        if (order.prescriptionAmount > 0) {
          items.push({ label: '处方费用', value: formatFee(order.prescriptionAmount) })
        }
        this.setData({
          order,
          detailItems: items,
          feeText: formatFee(order.fee),
          rxFeeText: formatFee(order.prescriptionAmount)
        })
      })
      .catch((err) => {
        if (isStale()) return
        toast(options.afterPay ? '支付成功，订单状态刷新失败，请稍后重新进入查看' : (err.message || '订单不存在'))
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
    if (this.data.paying || !this.data.order || !this.data.order.canPay) return
    const { payMethod } = this.data
    if (needsPayDrawer(payMethod)) {
      this.setData({ payDrawerVisible: true, payDrawerMethod: payMethod })
      return
    }
    this.doPay()
  },

  async doPay() {
    if (this.data.paying || !this.data.order || !this.data.order.canPay) return
    const { payMethod, order, balance } = this.data
    if (payMethod === 'balance') {
      if (!this.balanceLoaded) {
        toast('余额读取失败，正在重试，请稍后再点击支付')
        this.loadBalance().then((ok) => {
          if (!ok && !this._disposed) toast('余额仍无法读取，请检查网络后重试')
        })
        return
      }
      if (Number(order.fee) > Number(balance)) {
        toast('余额不足，请先充值')
        return
      }
    }
    this.setData({ paying: true })
    let paid = false
    try {
      const mode = await resolvePayMode(payMethod)
      if (mode === 'NATIVE') {
        await payNative({ businessType: 'APPOINTMENT', businessId: order.id })
      } else {
        await mockChannelPay(payMethod)
        await api.payAppointment(order.id, payMethod)
      }
      paid = true
      try {
        await auth.fetchMe()
      } catch (e) {
        // 支付已成功，仅余额刷新失败，不能提示支付失败
      }
      this.setData({ payDrawerVisible: false })
      wx.showToast({ title: '支付成功', icon: 'success' })
      if (this._disposed) return
      this.loadOrder(order.id, { afterPay: true })
      this.loadBalance()
    } catch (err) {
      if (paid) return
      toast(err.message || '支付失败')
      if (err && (err.code === 'PAYMENT_PENDING' || err.code === 'PAYMENT_REVIEW')) {
        // 结果待确认或待人工处理时关闭抽屉并刷新订单，避免重复付款
        this.setData({ payDrawerVisible: false })
        if (!this._disposed) this.loadOrder(order.id)
      }
    } finally {
      this.setData({ paying: false })
    }
  },

  onCancel() {
    wx.showModal({
      title: '取消预约',
      content: '确定要取消该预约吗？',
      success: (res) => {
        if (res.confirm) {
          api.cancelAppointment(this.data.order.id)
            .then(() => {
              this.loadOrder(this.data.order.id)
              wx.showToast({ title: '已取消', icon: 'success' })
            })
            .catch((err) => toast(err.message || '取消失败'))
        }
      }
    })
  },

  onEvaluate() {
    wx.navigateTo({ url: `/pages/order/evaluate?id=${this.data.order.id}` })
  },

  onCheckIn() {
    const { order } = this.data
    api.checkInAppointment(order.id)
      .then(() => {
        wx.showToast({ title: '签到成功', icon: 'success' })
        this.loadOrder(order.id)
      })
      .catch((err) => toast(err.message || '签到失败'))
  },

  onViewRecord() {
    wx.navigateTo({ url: `/pages/visit/record?id=${this.data.order.id}` })
  },

  onViewPrescription() {
    const { order } = this.data
    if (order.canPayPrescription) {
      wx.navigateTo({ url: `/pages/prescription/detail?id=${order.prescriptionId}&appointmentId=${order.id}` })
    } else {
      wx.navigateTo({ url: `/pages/prescription/detail?appointmentId=${order.id}` })
    }
  },

  onPrescription() {
    this.onViewPrescription()
  },

  onRecharge() {
    wx.navigateTo({ url: '/pages/recharge/recharge' })
  }
})
