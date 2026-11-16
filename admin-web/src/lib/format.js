export const roleLabel = role => ({ ADMIN: '管理员', DOCTOR: '医生', PHARMACIST: '配药师', USER: '用户' }[role] || role || '—')
export const money = value => new Intl.NumberFormat('zh-CN', { style: 'currency', currency: 'CNY' }).format(Number(value || 0))
export const dateTime = value => value ? String(value).replace('T', ' ').slice(0, 19) : '—'
export const appointmentOptions = [
  ['unpaid', '待支付'], ['pending', '待就诊'], ['confirmed', '已确认'], ['checked_in', '已到院'],
  ['in_consultation', '就诊中'], ['pending_rx_pay', '待缴药费'], ['pending_pickup', '待发药'],
  ['completed', '已完成'], ['cancelled', '已取消'],
].map(([value, label]) => ({ value, label }))
export const prescriptionLabels = { unpaid: '待缴药费', paid: '待发药', dispensed: '已发药', cancelled: '已作废' }

const appointmentStatuses = new Set(appointmentOptions.map(item => item.value))
const prescriptionStatuses = new Set(Object.keys(prescriptionLabels))
const pendingPrescriptionStatuses = new Set(['pending_rx_pay', 'pending_pickup'])
const activePrescriptionStatuses = new Set(['unpaid', 'paid', 'dispensed'])

export function appointmentStatus(row) {
  const status = typeof row === 'string' ? row : row?.status
  return status === 'pending_rx_pay' && row?.prescriptionStatus === 'paid' ? 'pending_pickup' : status
}

export function appointmentIssue(row) {
  const status = typeof row === 'string' ? row : row?.status
  if (!status) return '预约状态缺失，请联系管理员核对'
  if (!appointmentStatuses.has(status)) return '预约状态无法识别，请联系管理员核对'
  if (typeof row === 'string') return ''

  const hasPrescriptionId = row?.prescriptionId != null && row.prescriptionId !== ''
  const prescriptionStatus = row?.prescriptionStatus
  const hasPrescriptionStatus = prescriptionStatus != null && prescriptionStatus !== ''
  const isPendingPrescription = pendingPrescriptionStatuses.has(status)

  if (isPendingPrescription && (!hasPrescriptionId || !hasPrescriptionStatus)) {
    return '缺少处方，暂时无法缴费或发药，请联系管理员核对'
  }
  if (status === 'pending_pickup' && prescriptionStatus !== 'paid') {
    if (prescriptionStatus === 'unpaid') return '处方尚未缴费，暂时无法发药，请联系管理员核对'
    if (prescriptionStatus === 'cancelled') return '处方已作废，暂时无法发药，请联系管理员核对'
    if (prescriptionStatus === 'dispensed') return '处方已发药，但预约仍待发药，请联系管理员核对'
    return '处方状态无法识别，暂时无法发药，请联系管理员核对'
  }
  if (status === 'pending_rx_pay' && !['unpaid', 'paid'].includes(prescriptionStatus)) {
    if (prescriptionStatus === 'dispensed') return '处方已发药，但预约仍待缴药费，请联系管理员核对'
    if (prescriptionStatus === 'cancelled') return '处方已作废，但预约仍待缴药费，请联系管理员核对'
    return '处方状态无法识别，暂时无法缴费，请联系管理员核对'
  }
  if (hasPrescriptionId !== hasPrescriptionStatus) {
    return hasPrescriptionStatus
      ? '缺少处方，暂时无法缴费或发药，请联系管理员核对'
      : '处方状态缺失，暂时无法缴费或发药，请联系管理员核对'
  }
  if (hasPrescriptionStatus && !prescriptionStatuses.has(prescriptionStatus)) {
    return '处方状态无法识别，请联系管理员核对'
  }
  if (status === 'completed' && prescriptionStatus === 'unpaid') {
    return '预约已完成，但处方尚未缴费，请联系管理员核对'
  }
  if (status === 'completed' && prescriptionStatus === 'paid') {
    return '预约已完成，但处方尚未发药，请联系管理员核对'
  }
  if (status === 'cancelled' && activePrescriptionStatuses.has(prescriptionStatus)) {
    return `预约已取消，但处方仍为“${prescriptionLabels[prescriptionStatus]}”，请联系管理员核对`
  }
  return ''
}

export function appointmentLabel(row) {
  return appointmentOptions.find(item => item.value === appointmentStatus(row))?.label || '未知状态'
}
export const visitTime = row => `${row?.scheduleDate || '—'} ${row?.timeSlot || ''}`.trim()
export function avatarUrl(value) {
  if (!value) return ''
  if (value.startsWith('/')) return value
  if (/^https?:\/\//i.test(value)) return value
  return ''
}
