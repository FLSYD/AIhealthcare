const auth = require('../../utils/auth')
const toast = require('../../utils/toast')
const api = require('../../utils/api')
const { enrichDoctor } = require('../../utils/adapter')
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

Page({
  data: {
    doctor: null,
    deptName: '',
    scheduleDate: '',
    timeSlot: '',
    scheduleId: 0,
    patientName: '',
    patientPhone: '',
    remark: '',
    feeText: '',
    feeAmount: '20.00',
    timeText: '',
    balance: '0.00',
    balanceLoaded: false,
    balanceLoading: false,
    payMethods: PAY_METHODS,
    payMethod: 'wechat',
    payMode: '',
    payTip: '',
    payDrawerVisible: false,
    payDrawerMethod: 'wechat',
    submitting: false,
    appointmentLocked: false
  },

  onLoad(options) {
    if (!auth.isLoggedIn()) {
      wx.showModal({
        title: '提示',
        content: '预约需要先登录，是否前往登录？',
        success: (res) => {
          if (res.confirm) wx.switchTab({ url: '/pages/mine/mine' })
          else wx.navigateBack()
        }
      })
    }
    const doctorId = Number(options.doctorId)
    const scheduleDate = options.scheduleDate || ''
    const timeSlot = options.timeSlot || ''
    const scheduleId = Number(options.scheduleId) || 0
    api.getDoctorById(doctorId)
      .then((doctor) => {
        const enriched = enrichDoctor(doctor, true)
        const feeAmount = Number(enriched.fee || 20).toFixed(2)
        this.setData({
          doctor: enriched,
          deptName: doctor.departmentName || '',
          scheduleDate,
          timeSlot,
          scheduleId,
          feeText: formatFee(feeAmount),
          feeAmount,
          timeText: formatDateTime(scheduleDate, timeSlot)
        })
      })
      .catch((err) => toast(err.message || '加载失败'))
    this.loadBalance()
  },

  onShow() {
    this.loadPayMode()
  },

  onUnload() {
    this._disposed = true
    clearTimeout(this._navTimer)
    this._navTimer = null
  },

  // 延时跳转到订单详情；页面卸载后不再执行
  scheduleOrderRedirect(apptId) {
    if (this._disposed) return
    clearTimeout(this._navTimer)
    this._navTimer = setTimeout(() => {
      this._navTimer = null
      if (this._disposed) return
      wx.redirectTo({ url: `/pages/order/detail?id=${apptId}` })
    }, 800)
  },

  // 读取微信 Native 配置，仅用于隐藏扫码模式下不可用的渠道和展示提示；失败时付款会重新读取并报错
  async loadPayMode() {
    const payMode = await loadPayMode()
    if (payMode && payMode !== this.data.payMode) {
      this.setData({ payMode })
      this.syncPayMethods()
    }
  },

  syncPayMethods() {
    const { payMode, balance, balanceLoaded } = this.data
    // 保留用户已选渠道，NATIVE 下支付宝仍可选（教学模拟）
    const { payMethod } = this.data
    this.setData({
      payMethods: decoratePayMethods(PAY_METHODS, { mode: payMode, balance: balanceLoaded ? balance : null }),
      payTip: payTipFor(payMethod, payMode)
    })
  },

  // 读取余额；失败时 balanceLoaded 保持 false，余额支付会提示重试而不是误报余额不足
  async loadBalance() {
    if (!auth.isLoggedIn()) return false
    if (this.data.balanceLoading) return this.data.balanceLoaded
    this.setData({ balanceLoading: true })
    try {
      const user = await auth.fetchMe()
      const balance = user && user.balance != null ? Number(user.balance).toFixed(2) : '0.00'
      this.setData({ balance, balanceLoaded: true })
      this.syncPayMethods()
      return true
    } catch (e) {
      this.setData({ balanceLoaded: false })
      return false
    } finally {
      this.setData({ balanceLoading: false })
    }
  },

  onPatientNameInput(e) {
    if (this.data.appointmentLocked || this.data.submitting) return
    this.setData({ patientName: e.detail })
  },

  onPatientPhoneInput(e) {
    if (this.data.appointmentLocked || this.data.submitting) return
    this.setData({ patientPhone: e.detail })
  },

  onRemarkInput(e) {
    if (this.data.appointmentLocked || this.data.submitting) return
    this.setData({ remark: e.detail })
  },

  onPayMethodTap(e) {
    const { id } = e.currentTarget.dataset
    if (!id) return
    this.setData({ payMethod: id, payTip: payTipFor(id, this.data.payMode) })
    if (needsPayDrawer(id)) {
      this.setData({ payDrawerVisible: true, payDrawerMethod: id })
    } else {
      this.setData({ payDrawerVisible: false })
    }
  },

  onPayDrawerClose() {
    this.setData({ payDrawerVisible: false })
  },

  onPayDrawerConfirm() {
    this.doSubmit()
  },

  onConfirm() {
    if (this.data.submitting) return
    const { payMethod } = this.data
    if (needsPayDrawer(payMethod)) {
      this.setData({ payDrawerVisible: true, payDrawerMethod: payMethod })
      return
    }
    this.doSubmit()
  },

  async doSubmit() {
    if (this.data.submitting) return
    const { scheduleId, patientName, patientPhone, remark, payMethod, feeAmount, balance } = this.data
    if (!patientName || !patientPhone) {
      toast('请填写就诊人信息')
      return
    }
    if (!auth.isLoggedIn()) {
      toast('请先登录')
      wx.switchTab({ url: '/pages/mine/mine' })
      return
    }
    if (payMethod === 'balance') {
      if (!this.data.balanceLoaded) {
        // 余额未读取成功时不能判断是否足够，先重试读取再让用户确认
        toast('余额读取失败，正在重试，请稍后再点击支付')
        this.loadBalance().then((ok) => {
          if (!ok) toast('余额仍无法读取，请检查网络后重试')
        })
        return
      }
      if (Number(feeAmount) > Number(balance)) {
        toast('余额不足，请先充值')
        return
      }
    }
    this.setData({ submitting: true })
    let paid = false
    try {
      const mode = await resolvePayMode(payMethod)
      let appt
      if (mode === 'NATIVE') {
        // 缓存必须绑定完整就诊信息；已建单后锁定输入，防止给另一位就诊人支付旧订单。
        const fingerprint = JSON.stringify([scheduleId, patientName, patientPhone, remark || ''])
        if (this.nativeAppointment && this.nativeAppointment.fingerprint !== fingerprint) {
          toast('已有待支付预约，请先到订单详情处理旧预约，再修改就诊信息')
          wx.redirectTo({ url: `/pages/order/detail?id=${this.nativeAppointment.appt.id}` })
          return
        }
        if (this.nativeAppointment) {
          appt = this.nativeAppointment.appt
        } else {
          appt = await api.createAppointment({ scheduleId, patientName, patientPhone, remark })
          this.nativeAppointment = { fingerprint, appt }
          this.setData({ appointmentLocked: true, patientName, patientPhone, remark })
        }
        this.pendingApptId = appt.id
        await payNative({ businessType: 'APPOINTMENT', businessId: appt.id })
        this.nativeAppointment = null
      } else {
        await mockChannelPay(payMethod)
        appt = await api.createAppointment({
          scheduleId,
          patientName,
          patientPhone,
          remark
        })
        await api.payAppointment(appt.id, payMethod)
      }
      paid = true
      try {
        await auth.fetchMe()
      } catch (e) {
        // 支付已成功，仅余额刷新失败，不能提示支付失败
      }
      this.setData({ payDrawerVisible: false })
      wx.showToast({ title: '支付成功', icon: 'success' })
      this.scheduleOrderRedirect(appt.id)
    } catch (err) {
      if (paid) return
      toast(err.message || '预约失败')
      // 支付结果待确认或待人工处理时关闭抽屉并进入订单页查看，避免重复付款
      if (err && (err.code === 'PAYMENT_PENDING' || err.code === 'PAYMENT_REVIEW')) {
        this.setData({ payDrawerVisible: false })
        const apptId = this.pendingApptId
        if (apptId) this.scheduleOrderRedirect(apptId)
      }
    } finally {
      this.setData({ submitting: false })
    }
  }
})
