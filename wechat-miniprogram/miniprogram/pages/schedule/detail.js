const api = require('../../utils/api')
const { enrichDoctor, groupSchedules } = require('../../utils/adapter')
const { buildDateStrip, formatFee } = require('../../utils/format')
const toast = require('../../utils/toast')

Page({
  data: {
    doctor: null,
    dateList: [],
    selectedDate: '',
    morning: [],
    afternoon: [],
    hasAnySchedule: false,
    hasAvailable: false,
    selectedTime: '',
    selectedScheduleId: 0,
    slotsLoading: false,
    slotsError: false,
    feeText: ''
  },

  onLoad(options) {
    const doctorId = Number(options.doctorId)
    if (!doctorId) {
      wx.showToast({ title: '参数错误', icon: 'none' })
      return
    }
    const dateList = buildDateStrip(7)
    const selectedDate = dateList[0].value
    this.setData({ dateList, selectedDate, doctorId })
    api.getDoctorById(doctorId)
      .then((doctor) => {
        this.setData({
          doctor: enrichDoctor(doctor, true),
          feeText: formatFee(20)
        })
        this.loadSlots()
      })
      .catch((err) => toast(err.message || '医生不存在'))
  },

  loadSlots() {
    const { doctorId, selectedDate } = this.data
    this._slotsSeq = (this._slotsSeq || 0) + 1
    const seq = this._slotsSeq
    // 进入加载态时立即清空旧日期的时段和选择，避免加载期间提交旧号源
    this.setData({
      slotsLoading: true,
      slotsError: false,
      morning: [],
      afternoon: [],
      hasAnySchedule: false,
      hasAvailable: false,
      selectedTime: '',
      selectedScheduleId: 0
    })
    return api.getSchedules({ doctorId, scheduleDate: selectedDate })
      .then((schedules) => {
        if (seq !== this._slotsSeq) return
        const slots = groupSchedules(schedules)
        this.setData({
          morning: slots.morning,
          afternoon: slots.afternoon,
          hasAnySchedule: slots.hasAnySchedule,
          hasAvailable: slots.hasAvailable,
          selectedTime: '',
          selectedScheduleId: 0,
          slotsLoading: false
        })
      })
      .catch((err) => {
        if (seq !== this._slotsSeq) return
        this.setData({ slotsLoading: false, slotsError: true })
        toast((err && err.message) || '加载号源失败')
      })
  },

  onDateChange(e) {
    this.setData({ selectedDate: e.detail.value })
    this.loadSlots()
  },

  onSlotSelect(e) {
    if (this.data.slotsLoading) return
    this.setData({
      selectedTime: e.detail.time,
      selectedScheduleId: e.detail.scheduleId
    })
  },

  onBook() {
    const { doctor, selectedDate, selectedTime, selectedScheduleId, slotsLoading, slotsError } = this.data
    if (slotsLoading) {
      wx.showToast({ title: '号源加载中，请稍候', icon: 'none' })
      return
    }
    if (slotsError) {
      wx.showToast({ title: '号源加载失败，请重新选择日期', icon: 'none' })
      return
    }
    if (!doctor || !doctor.id) return
    if (!selectedTime || !selectedScheduleId) {
      wx.showToast({ title: '请选择就诊时段', icon: 'none' })
      return
    }
    wx.navigateTo({
      url: `/pages/appointment/confirm?doctorId=${doctor.id}&scheduleDate=${selectedDate}&timeSlot=${selectedTime}&scheduleId=${selectedScheduleId}`
    })
  }
})
