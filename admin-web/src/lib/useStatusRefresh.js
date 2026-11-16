import { onBeforeUnmount, onMounted } from 'vue'

// 仅在页面可见时同步，避免患者在另一端缴费或签到后，后台一直停留在旧状态。
export function useStatusRefresh(refresh, { enabled = () => true, interval = 15000 } = {}) {
  let timer
  let running = false
  let disposed = false
  async function sync() {
    if (disposed || running || document.hidden || !enabled()) return
    running = true
    try { await refresh() } catch { /* 页面自身负责显示同步错误。 */ }
    finally { running = false }
  }
  onMounted(() => {
    timer = window.setInterval(sync, interval)
    window.addEventListener('focus', sync)
    document.addEventListener('visibilitychange', sync)
  })
  onBeforeUnmount(() => {
    disposed = true
    window.clearInterval(timer)
    window.removeEventListener('focus', sync)
    document.removeEventListener('visibilitychange', sync)
  })
}
