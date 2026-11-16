import { api, loadAll } from './api'
import { appointmentStatus } from './format'
import { usePagedList } from './usePagedList'

const prescriptionListStatuses = new Set(['pending_rx_pay', 'pending_pickup'])

function text(value) {
  return value == null ? '' : String(value)
}

function compareTextDescending(left, right) {
  return text(right).localeCompare(text(left), 'zh-CN')
}

function compareIdDescending(left, right) {
  const leftId = text(left).trim()
  const rightId = text(right).trim()
  const leftDigits = /^\d+$/.test(leftId)
  const rightDigits = /^\d+$/.test(rightId)
  if (leftDigits && rightDigits) {
    const leftNormalized = leftId.replace(/^0+(?=\d)/, '')
    const rightNormalized = rightId.replace(/^0+(?=\d)/, '')
    if (leftNormalized.length !== rightNormalized.length) return rightNormalized.length - leftNormalized.length
    return rightNormalized.localeCompare(leftNormalized)
  }
  return compareTextDescending(leftId, rightId)
}

function mergeAppointments(groups, targetStatus) {
  const uniqueRows = new Map()
  let anonymousIndex = 0
  for (const row of groups.flat()) {
    const key = row?.id != null && row.id !== '' ? `id:${String(row.id)}` : `anonymous:${anonymousIndex++}`
    const previous = uniqueRows.get(key)
    // 两个查询之间可能刚好完成缴费；优先保留已经推进到待取药的那份记录。
    const rank = value => value?.status === 'pending_pickup' ? 2 : value?.prescriptionStatus === 'paid' ? 1 : 0
    if (!previous || rank(row) > rank(previous)
      || (rank(row) === rank(previous) && String(row?.updateTime || '') > String(previous?.updateTime || ''))) {
      uniqueRows.set(key, row)
    }
  }
  return [...uniqueRows.values()]
    .filter(row => appointmentStatus(row) === targetStatus)
    .sort((left, right) =>
      compareTextDescending(left?.scheduleDate, right?.scheduleDate) ||
      compareTextDescending(left?.timeSlot, right?.timeSlot) ||
      compareIdDescending(left?.id, right?.id),
    )
}

export function useAppointmentList(endpoint, initialFilters = {}) {
  async function fetchPage(params = {}, requestOptions = {}) {
    const path = typeof endpoint === 'function' ? endpoint() : endpoint
    const status = params.status
    if (!prescriptionListStatuses.has(status)) return api.get(path, { ...requestOptions, params })

    const { status: ignoredStatus, pageNum, pageSize, ...otherFilters } = params
    const [pendingRxPayRows, pendingPickupRows] = await Promise.all([
      loadAll(path, { ...otherFilters, status: 'pending_rx_pay' }, requestOptions),
      loadAll(path, { ...otherFilters, status: 'pending_pickup' }, requestOptions),
    ])
    const rows = mergeAppointments([pendingRxPayRows, pendingPickupRows], status)
    const currentPage = Math.max(1, Number(pageNum) || 1)
    const currentSize = Math.max(1, Number(pageSize) || 10)
    const start = (currentPage - 1) * currentSize
    return { list: rows.slice(start, start + currentSize), total: rows.length }
  }

  return usePagedList(endpoint, initialFilters, { fetchPage })
}
