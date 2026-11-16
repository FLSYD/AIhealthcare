const api = require('../../../utils/api')
const auth = require('../../../utils/auth')
const toast = require('../../../utils/toast')
const { formatServerDateTime } = require('../../../utils/format')

const PAGE_SIZE = 20

Page({
  data: {
    loading: false,
    loadingMore: false,
    hasMore: false,
    loadError: false,
    list: []
  },

  onShow() {
    if (!auth.isLoggedIn()) {
      toast('请先登录')
      wx.switchTab({ url: '/pages/mine/mine' })
      return
    }
    this.loadList()
  },

  // 重新加载第一页并重置页码
  loadList() {
    this._seq = (this._seq || 0) + 1
    this._current = 0
    this.setData({ list: [], loading: true, loadingMore: false, hasMore: false, loadError: false })
    return this.fetchPage(this._seq)
  },

  fetchPage(seq) {
    const page = (this._current || 0) + 1
    return api.getNotifications({ current: page, size: PAGE_SIZE })
      .then((result) => {
        if (seq !== this._seq) return
        const records = (result && result.records) || []
        const total = Number(result && result.total) || 0
        this._current = page
        const known = {}
        this.data.list.forEach((item) => { known[item.id] = true })
        const incoming = []
        records.forEach((item) => {
          if (known[item.id]) return
          known[item.id] = true
          incoming.push({ ...item, timeText: formatServerDateTime(item.createTime) })
        })
        this.setData({
          list: this.data.list.concat(incoming),
          hasMore: records.length >= PAGE_SIZE && (!total || page * PAGE_SIZE < total),
          loadError: false
        })
      })
      .catch((err) => {
        if (seq !== this._seq) return
        this.setData({ loadError: true })
        toast((err && err.message) || '加载失败')
      })
      .finally(() => {
        if (seq === this._seq) this.setData({ loading: false, loadingMore: false })
      })
  },

  onReachBottom() {
    const { hasMore, loading, loadingMore, loadError } = this.data
    if (!hasMore || loading || loadingMore || loadError) return
    this.setData({ loadingMore: true })
    this.fetchPage(this._seq)
  },

  onRetry() {
    if (this.data.loading || this.data.loadingMore) return
    if (!this.data.list.length && !this._current) {
      this.loadList()
      return
    }
    this.setData({ loadingMore: true, loadError: false })
    this.fetchPage(this._seq)
  },

  onReadAll() {
    api.markAllNotificationsRead()
      .then(() => {
        wx.showToast({ title: '已全部已读', icon: 'success' })
        this.loadList()
      })
      .catch((err) => toast(err.message || '操作失败'))
  },

  onItemTap(e) {
    const { item } = e.currentTarget.dataset
    if (!item) return
    const go = () => {
      if (item.bizType === 'appointment' && item.bizId) {
        wx.navigateTo({ url: `/pages/order/detail?id=${item.bizId}` })
      }
    }
    if (!item.read) {
      api.markNotificationRead(item.id)
        .then(go)
        .catch(go)
    } else {
      go()
    }
  }
})