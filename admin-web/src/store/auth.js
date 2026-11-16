import { reactive } from 'vue'
import { api, TOKEN_KEY, cancelPendingConfirmations } from '../lib/api'

export const auth = reactive({ user: null, permissions: null, menus: [], ready: false })
let pending
let sessionVersion = 0
let sessionToken = null
export function invalidateSession() {
  cancelPendingConfirmations()
  sessionVersion++
  sessionToken = null
  auth.user = null
  auth.permissions = null
  auth.menus = []
  auth.ready = false
  pending = null
}
export function clearSession() {
  invalidateSession()
  localStorage.removeItem(TOKEN_KEY)
}
export async function loadSession(force = false) {
  if (!localStorage.getItem(TOKEN_KEY)) { clearSession(); return false }
  if (auth.ready && sessionToken !== localStorage.getItem(TOKEN_KEY)) invalidateSession()
  if (auth.ready && !force) return true
  const token = localStorage.getItem(TOKEN_KEY), version = sessionVersion
  const current = () => version === sessionVersion && token === localStorage.getItem(TOKEN_KEY)
  if (pending?.token === token && pending.version === version) return pending.promise
  const task = { token, version, promise: null }
  task.promise = (async () => {
    try {
      const [user, permissions] = await Promise.all([api.get('/me'), api.get('/permissions')])
      if (!current()) throw new Error('会话已切换，请重新操作')
      if (!['ADMIN', 'DOCTOR', 'PHARMACIST'].includes(user.role) || !permissions.backendAccess) {
        await api.post('/logout').catch(() => {})
        if (current()) clearSession()
        throw new Error('当前账号不具备后台权限，请使用管理员、医生或配药师账号')
      }
      auth.user = user
      auth.permissions = permissions
      auth.menus = Array.isArray(permissions.menus) ? permissions.menus : []
      sessionToken = token
      auth.ready = true
      return true
    } catch (error) {
      // 仅明确 401 清除登录态；网络错误、超时、5xx 保留 token 以便重试
      if (current() && error.response?.status === 401) clearSession()
      throw error
    }
    finally { if (pending === task) pending = null }
  })()
  pending = task
  return task.promise
}
export async function signIn(username, password) {
  clearSession()
  const version = sessionVersion
  const token = await api.post('/login', { username: username.trim(), password })
  if (version !== sessionVersion) throw new Error('会话已切换，请重新登录')
  if (typeof token !== 'string' || !token) throw new Error('登录响应异常，请稍后重试')
  localStorage.setItem(TOKEN_KEY, token)
  await loadSession(true)
  return auth.permissions.homePath
}
export async function signOut() {
  const token = localStorage.getItem(TOKEN_KEY)
  try { await api.post('/logout') } finally { if (localStorage.getItem(TOKEN_KEY) === token) clearSession() }
}
export async function refreshMenus() {
  const version = sessionVersion, token = localStorage.getItem(TOKEN_KEY)
  const permissions = await api.get('/permissions')
  if (version !== sessionVersion || token !== localStorage.getItem(TOKEN_KEY)) return
  auth.permissions = permissions
  auth.menus = Array.isArray(permissions.menus) ? permissions.menus : []
}
