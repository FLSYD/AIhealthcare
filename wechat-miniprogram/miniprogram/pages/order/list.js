const { setTabBar } = require('../../utils/tab')
const auth = require('../../utils/auth')
const api = require('../../utils/api')
const { toOrder } = require('../../utils/adapter')
const toast = require('../../utils/toast')

const PAGE_SIZE = 20
// 客户端状态过滤可能让整页为空，单次最多连续补取的页数
const MAX_FILL_PAGES = 5

function matchOrderTab(order, tabKey) {
  if (tabKey === 'all') return true
  const status = order.rawStatus || order.status
  const display = order.displayStatus || order.status
  if (tabKey === 'pending') {
    return status === 'pending' || status === 'confirmed'
  }
  if (tabKey === 'pending_dispense') {
    return display === 'pending_dispense'
  }
  if (tabKey === 'pending_rx_pay') {
    return status === 'pending_rx_pay' && display !== 'pending_dispense'
  }
  return display === tabKey || status === tabKey
}

// 订单 ID 必须是正整数，防止拼出 id=undefined 之类的无效地址
function normalizeId(id) {
  const text = String(id == null ? '' : id).trim()
  return /^[1-9]\d*$/.test(text) ? text : ''
}

const EMPTY_TEXT = {
  all: '暂无订单'
}

Page({
  data: {
    activeTab: 0,
    tabs: [
      { key: 'all', title: '全部' },
      { key: 'unpaid', title: '待支付' },
      { key: 'pending', title: '待就诊' },
      { key: 'checked_in', title: '已到院' },
      { key: 'in_consultation', title: '就诊中' },
      { key: 'pending_rx_pay', title: '待缴药费' },
      { key: 'pending_dispense', title: '待取药' },
      { key: 'completed', title: '已完成' },
      { key: 'cancelled', title: '已取消' }
    ],
    orders: [],
    loggedIn: false,
    loading: false,
    loadingMore: false,
    loadError: false,
    loaded: false,
    hasMore: false,
    emptyText: '暂无订单'
  },

  onShow() {
    setTabBar(this, '/pages/order/list')
    // 从详情页等返回时解除导航锁
    this._navLocked = false
    const loggedIn = auth.isLoggedIn()
    this.setData({ loggedIn })
    if (!loggedIn) {
      this._seq = (this._seq || 0) + 1
      this.setData({ orders: [], loading: false, loadingMore: false, loadError: false, loaded: false, hasMore: false })
      return
    }
    this.loadOrders()
  },

  onGoLogin() {
    wx.switchTab({ url: '/pages/mine/mine' })
  },

  currentTabKey(tabIndex) {
    const index = tabIndex != null ? tabIndex : this.data.activeTab
    return this.data.tabs[index].key
  },

  // 重新加载第一页：作废在途请求并重置页码和列表
  loadOrders(tabIndex) {
    const key = this.currentTabKey(tabIndex)
    this._seq = (this._seq || 0) + 1
    this._current = 0
    this.setData({
      orders: [],
      loading: true,
      loadingMore: false,
      loadError: false,
      loaded: false,
      hasMore: false,
      emptyText: EMPTY_TEXT[key] || '该状态下暂无订单'
    })
    this.fetchNext(key, this._seq)
  },

  // 拉取下一页，过滤后仍为空且还有下一页时继续补取
  fetchNext(key, seq, filled) {
    const nextPage = (this._current || 0) + 1
    const params = { current: nextPage, size: PAGE_SIZE }
    if (key !== 'all') params.status = key
    return api.getAppointments(params)
      .then((page) => {
        if (seq !== this._seq) return
        const records = (page && page.records) || []
        const total = Number(page && page.total) || 0
        this._current = nextPage
        const hasMore = records.length >= PAGE_SIZE && (!total || nextPage * PAGE_SIZE < total)
        const known = {}
        this.data.orders.forEach((order) => { known[order.id] = true })
        const incoming = []
        records.map(toOrder).filter((order) => matchOrderTab(order, key)).forEach((order) => {
          if (known[order.id]) return
          known[order.id] = true
          incoming.push(order)
        })
        const orders = this.data.orders.concat(incoming)
        const pages = (filled || 0) + 1
        if (!orders.length && hasMore && pages < MAX_FILL_PAGES) {
          this.setData({ hasMore })
          return this.fetchNext(key, seq, pages)
        }
        this.setData({ orders, hasMore, loading: false, loadingMore: false, loadError: false, loaded: true })
      })
      .catch((err) => {
        if (seq !== this._seq) return
        this.setData({ loading: false, loadingMore: false, loadError: true, loaded: true })
        toast((err && err.message) || '加载失败')
      })
  },

  onReachBottom() {
    const { loggedIn, hasMore, loading, loadingMore, loadError } = this.data
    if (!loggedIn || !hasMore || loading || loadingMore || loadError) return
    this.setData({ loadingMore: true })
    this.fetchNext(this.currentTabKey(), this._seq)
  },

  // 失败后重试：首页失败重载，翻页失败继续取下一页
  onRetry() {
    if (this.data.loading || this.data.loadingMore) return
    if (!this.data.orders.length && !this._current) {
      this.loadOrders()
      return
    }
    this.setData({ loadingMore: true, loadError: false })
    this.fetchNext(this.currentTabKey(), this._seq)
  },

  onTabChange(e) {
    const { index, name } = e.detail || {}
    let tabIndex = typeof index === 'number' ? index : this.data.activeTab
    if (name) {
      const found = this.data.tabs.findIndex((tab) => tab.key === name)
      if (found >= 0) tabIndex = found
    }
    this.setData({ activeTab: tabIndex })
    this.loadOrders(tabIndex)
  },

  // 带 ID 校验和防连点的页面跳转；失败时立即解锁，成功返回后由 onShow 解锁
  openPage(path, id) {
    const safeId = normalizeId(id)
    if (!safeId) {
      toast('订单信息异常，请刷新后重试')
      return
    }
    if (this._navLocked) return
    this._navLocked = true
    wx.navigateTo({
      url: `${path}?id=${safeId}`,
      fail: () => {
        this._navLocked = false
        toast('打开失败，请重试')
      }
    })
  },

  onOrderTap(e) {
    this.openPage('/pages/order/detail', e.detail && e.detail.id)
  },

  onOrderAction(e) {
    const { action, id } = e.detail || {}
    if (action === 'pay' || action === 'rx') {
      this.openPage('/pages/order/detail', id)
      return
    }
    if (action === 'cancel') {
      const safeId = normalizeId(id)
      if (!safeId) {
        toast('订单信息异常，请刷新后重试')
        return
      }
      wx.showModal({
        title: '取消预约',
        content: '确定要取消该预约吗？',
        success: (res) => {
          if (res.confirm) {
            api.cancelAppointment(safeId)
              .then(() => {
                wx.showToast({ title: '已取消', icon: 'success' })
                this.loadOrders()
              })
              .catch((err) => toast(err.message || '取消失败'))
          }
        }
      })
    } else if (action === 'evaluate') {
      this.openPage('/pages/order/evaluate', id)
    }
  }
})