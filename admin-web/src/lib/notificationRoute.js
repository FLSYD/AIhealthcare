// 消息通知跳转与配药工作台 query 解析的纯函数，便于脱离 Vue 单独测试。
// 通知类型兜底文案：后端未返回 typeText 时按 type 显示。
export const notificationTypeLabels = {
  prescription_paid: '处方已缴费',
  prescription_dispensed: '处方已发药',
}

export function notificationTypeText(item) {
  return item?.typeText || notificationTypeLabels[item?.type] || ''
}

const PRESCRIPTION_ID = /^[1-9]\d*$/

// 配药工作台允许的队列状态。
export const pharmacyStatuses = ['paid', 'dispensed']

// 解析 /pharmacy 的 query：id 必须是正整数，status 只接受 paid / dispensed。
// 数组形式的 query（?id=1&id=2）取第一个值，其余视为无效输入。
export function parsePharmacyQuery(query) {
  const first = value => (Array.isArray(value) ? value[0] : value)
  const rawId = first(query?.id)
  const rawStatus = first(query?.status)
  const hasId = rawId !== undefined && rawId !== null && rawId !== ''
  const id = hasId && PRESCRIPTION_ID.test(String(rawId)) ? String(rawId) : null
  const status = pharmacyStatuses.includes(rawStatus) ? rawStatus : null
  return { id, status, invalidId: hasId && !id }
}

// 计算通知的跳转目标；返回 null 表示该通知对当前角色没有可跳转的页面。
// 处方通知只对配药师开放，避免其他角色被带到无权访问的页面。
export function getNotificationTarget(item, role) {
  if (!item?.bizType) return null
  const bizId = item.bizId === undefined || item.bizId === null ? '' : String(item.bizId)
  if (item.bizType === 'prescription') {
    if (role !== 'PHARMACIST' || !PRESCRIPTION_ID.test(bizId)) return null
    const query = { id: bizId }
    if (item.type === 'prescription_dispensed') query.status = 'dispensed'
    return { path: '/pharmacy', query }
  }
  if (item.bizType === 'appointment' && bizId) {
    if (role === 'PHARMACIST') return { path: '/pharmacy' }
    return { path: role === 'DOCTOR' ? '/doctor/appointments' : '/appointment', query: { id: bizId } }
  }
  if (item.bizType === 'schedule') {
    return { path: role === 'DOCTOR' ? '/doctor/schedules' : role === 'ADMIN' ? '/schedule' : '/pharmacy' }
  }
  return null
}
