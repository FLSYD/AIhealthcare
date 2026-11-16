import axios from 'axios'
import { ElMessage, ElMessageBox } from 'element-plus'

export const TOKEN_KEY = 'healthcare-admin-token'
export const api = axios.create({ baseURL: '/api', timeout: 20000 })

api.interceptors.request.use(config => {
  const token = localStorage.getItem(TOKEN_KEY)
  config.healthcareToken = token
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})
api.interceptors.response.use(response => {
  const requestToken = response.config.healthcareToken
  if (requestToken && requestToken !== localStorage.getItem(TOKEN_KEY)) return Promise.reject(new Error('旧会话请求已失效'))
  const body = response.data
  if (body?.code === 200) return body.data
  const error = new Error(body?.msg || body?.message || '操作未成功，请重试')
  if (!response.config.quiet) ElMessage.error(error.message)
  return Promise.reject(error)
}, error => {
  const requestToken = error.config?.healthcareToken
  if (requestToken && requestToken !== localStorage.getItem(TOKEN_KEY)) return Promise.reject(new Error('旧会话请求已失效'))
  if (error.response?.status === 401) {
    if (requestToken) {
      localStorage.removeItem(TOKEN_KEY)
      window.dispatchEvent(new CustomEvent('healthcare-session-expired'))
    }
    error.message = '登录已过期，请重新登录'
  } else {
    error.message = error.response?.data?.msg || error.response?.data?.message ||
      (error.response ? '服务处理失败，请稍后重试' :
        error.code === 'ECONNABORTED' ? '请求超时，请稍后重试' : '无法连接服务，请确认后端正在运行')
  }
  if (!error.config?.quiet) ElMessage.error(error.message)
  return Promise.reject(error)
})

let confirmationVersion = 0
const pendingConfirmations = new Set()
export function cancelPendingConfirmations() {
  confirmationVersion++
  for (const cancel of pendingConfirmations) cancel(false)
  pendingConfirmations.clear()
  ElMessageBox.close()
}

export async function confirmAction(message, title = '操作确认') {
  const token = localStorage.getItem(TOKEN_KEY), version = confirmationVersion
  let cancel
  const cancelled = new Promise(resolve => { cancel = resolve; pendingConfirmations.add(cancel) })
  try {
    const confirmed = ElMessageBox.confirm(message, title, { confirmButtonText: '确定', cancelButtonText: '取消', type: 'warning' }).then(() => true, () => false)
    return await Promise.race([confirmed, cancelled]) && version === confirmationVersion && token === localStorage.getItem(TOKEN_KEY)
  } catch { return false } finally { pendingConfirmations.delete(cancel) }
}

export async function runAction(action, message = '操作成功') {
  try {
    const result = await action()
    if (message) ElMessage.success(message)
    return { ok: true, data: result }
  } catch { return { ok: false } }
}

export async function loadAll(path, params = {}, requestOptions = {}) {
  const rows = []
  for (let pageNum = 1; pageNum <= 100; pageNum++) {
    const result = await api.get(path, { ...requestOptions, params: { ...params, pageNum, pageSize: 100 } })
    const list = result?.list || []
    rows.push(...list)
    if (rows.length >= result.total || list.length < 100) return rows
  }
  throw new Error('候选数据过多，请缩小查询范围')
}
