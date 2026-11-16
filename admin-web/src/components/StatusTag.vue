<script setup>
import { computed } from 'vue'
import { appointmentIssue, appointmentLabel, appointmentStatus, prescriptionLabels } from '../lib/format'
const props = defineProps({ status: [String, Number], kind: { type: String, default: 'account' }, row: Object })
const label = computed(() => props.kind === 'appointment' ? appointmentLabel(props.row || props.status) : props.kind === 'prescription' ? (prescriptionLabels[props.status] || '—') : props.kind === 'schedule' ? (Number(props.status) === 1 ? '开放' : '停诊') : (Number(props.status) === 1 ? '启用' : '停用'))
const issue = computed(() => props.kind === 'appointment' ? appointmentIssue(props.row || props.status) : '')
const tone = computed(() => {
  if (props.kind === 'account' || props.kind === 'schedule') return Number(props.status) === 1 ? 'success' : 'info'
  if (issue.value) return 'danger'
  if (props.kind === 'appointment') {
    const status = appointmentStatus(props.row || props.status)
    if (status === 'completed') return 'success'
    if (status === 'cancelled') return 'info'
    if (['unpaid', 'pending_rx_pay'].includes(status)) return 'danger'
    if (['pending', 'confirmed', 'checked_in', 'in_consultation', 'pending_pickup'].includes(status)) return 'warning'
    return 'info'
  }
  if (props.status === 'dispensed') return 'success'
  if (props.status === 'cancelled') return 'info'
  if (['unpaid', 'paid'].includes(props.status)) return 'warning'
  return 'info'
})
</script>
<template>
  <el-tooltip :content="issue" :disabled="!issue" placement="top" effect="dark">
    <el-tag :type="tone" effect="light" round size="small">
      {{ label }}<span v-if="issue" class="status-tag-issue"> · 异常</span>
    </el-tag>
  </el-tooltip>
</template>
<style scoped>.status-tag-issue{font-weight:700}</style>
