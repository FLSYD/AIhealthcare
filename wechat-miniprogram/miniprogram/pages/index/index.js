const { getStatusBarHeight } = require('../../utils/system')
const { setTabBar, goDeptTab } = require('../../utils/tab')
const api = require('../../utils/api')
const { enrichDepartments, enrichDoctor } = require('../../utils/adapter')
const toast = require('../../utils/toast')

Page({
  data: {
    statusBarHeight: 20,
    keyword: '',
    departments: [],
    doctors: []
  },

  onLoad() {
    this.setData({ statusBarHeight: getStatusBarHeight() })
    this.loadData()
  },

  loadData() {
    Promise.all([
      api.getDepartments(),
      api.getDoctors({ hasAvailableSlot: true })
    ])
      .then(([departments, doctors]) => {
        this.setData({
          departments: enrichDepartments(departments),
          doctors: (doctors || []).map((d) => enrichDoctor(d, true)).slice(0, 3)
        })
      })
      .catch((err) => toast(err.message || '加载失败'))
  },

  onShow() {
    setTabBar(this, '/pages/index/index')
    this._doctorNavigating = false
    this.setData({ keyword: '' })
  },

  onSearchChange(e) {
    this.setData({ keyword: e.detail.value || '' })
  },

  onSearch(e) {
    const keyword = (e.detail.value || '').trim()
    goDeptTab({ keyword })
  },

  onDeptSelect(e) {
    const { id } = e.detail
    goDeptTab({ deptId: id })
  },

  onDoctorTap(e) {
    const id = Number(e && e.detail && e.detail.id)
    if (!id || this._doctorNavigating) return
    this._doctorNavigating = true
    wx.navigateTo({
      url: `/pages/schedule/detail?doctorId=${id}`,
      fail: () => { this._doctorNavigating = false }
    })
  },

  onMoreDept() {
    goDeptTab()
  },

  onMoreDoctor() {
    goDeptTab()
  }
})
