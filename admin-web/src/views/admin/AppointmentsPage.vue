<script setup>
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRoute } from 'vue-router'
import { Search, Refresh } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { api, confirmAction, TOKEN_KEY } from '../../lib/api'
import { registerNavigationGuard } from '../../lib/navigation'
import { appointmentOptions, appointmentIssue, money, dateTime, visitTime } from '../../lib/format'
import { useAppointmentList } from '../../lib/useAppointmentList'
import { useStatusRefresh } from '../../lib/useStatusRefresh'
import PageHeading from '../../components/PageHeading.vue'
import DataPagination from '../../components/DataPagination.vue'
import StatusTag from '../../components/StatusTag.vue'
const route = useRoute()
const { rows, total, page, size, filters, loading, error, load, search, reset } = useAppointmentList('/appointment/page', { keyword: '', status: '', scheduleDate: '' })
const visible = ref(false), detail = ref(null), detailLoading = ref(false), detailError = ref(''), detailRowId = ref(null), busy = ref(false)
let requestId = 0
const validRow = row => !error.value && !(visible.value && detailError.value && Number(row?.id) === Number(detailRowId.value)) && !appointmentIssue(row)
const canCancel = row => validRow(row) && ['unpaid', 'pending', 'confirmed', 'checked_in'].includes(row.status) && !['paid', 'dispensed'].includes(row.prescriptionStatus)
const canComplete = row => validRow(row) && ['pending', 'confirmed', 'in_consultation'].includes(row.status) && row.prescriptionStatus !== 'unpaid'
const canDispense = row => validRow(row) && !!row.prescriptionId && row.prescriptionStatus === 'paid' && ['pending_rx_pay', 'pending_pickup'].includes(row.status)
useStatusRefresh(async () => {
  await load({ background: true })
  if (visible.value && detail.value?.id) {
    const id = detail.value.id, current = ++requestId
    try {
      const result = await api.get(`/appointment/${id}`, { quiet: true })
      if (current === requestId && visible.value) { detail.value = result; detailError.value = '' }
    } catch (failure) {
      if (current === requestId && visible.value) detailError.value = `状态同步失败，请刷新后操作：${failure.message}`
    }
  }
}, { enabled: () => !busy.value && !detailLoading.value && !loading.value })
async function open(row, refreshAfterAction = false) {
  if (busy.value && !refreshAfterAction) return
  const id = row?.id
  if (!id) return
  const current = ++requestId; visible.value = true; detailRowId.value = id; detail.value = null; detailError.value = ''; detailLoading.value = true
  try { const result = await api.get(`/appointment/${id}`); if (current === requestId) detail.value = result } catch (failure) { if (current === requestId) detailError.value = failure.message } finally { if (current === requestId) detailLoading.value = false }
}
async function action(row, type) {
  if (busy.value || loading.value || detailLoading.value || !({ cancel: canCancel, complete: canComplete, dispense: canDispense }[type]?.(row))) return
  const id = row.id
  const prompt = { cancel: '确定取消此预约吗？系统会按当前支付状态处理挂号费并释放号源。', complete: '确定标记就诊完成吗？完成后将不能继续修改诊疗状态。', dispense: '确认处方已缴费并完成实际发药？系统将扣减药品库存并结束本次就诊。' }[type]
  busy.value = true
  try {
    if (!await confirmAction(prompt)) return
    const latest = await api.get(`/appointment/${id}`)
    if (!({ cancel: canCancel, complete: canComplete, dispense: canDispense }[type]?.(latest))) {
      ElMessage.warning('预约状态已变化，请按最新状态操作')
      await load()
      if (visible.value && detail.value?.id === id) await open({ id }, true)
      return
    }
    await api.put(type === 'dispense' ? `/appointment/prescription/${latest.prescriptionId}/dispense` : `/appointment/${id}/${type}`)
    ElMessage.success(type === 'cancel' ? '预约已取消' : type === 'dispense' ? '已标记取药完成' : '预约已完成')
    await load()
    if (visible.value && detail.value?.id === id) await open({ id }, true)
  } catch {} finally { busy.value = false }
}
function routeDetail() { const id = Number(route.query.id); if (Number.isSafeInteger(id) && id > 0) open({ id }); else visible.value = false }
watch(() => route.query.id, routeDetail)
watch(visible, value => { if (!value) { requestId++; detailLoading.value = false; detailError.value = ''; detail.value = null; detailRowId.value = null } })
function allowLeave(to) {
  if (to?.path === '/login' && !localStorage.getItem(TOKEN_KEY)) return true
  if (busy.value) { ElMessage.warning('当前预约操作尚未完成，请稍候'); return false }
  return true
}
onBeforeRouteLeave(allowLeave)
onBeforeRouteUpdate(allowLeave)
const unregister = registerNavigationGuard(allowLeave)
onBeforeUnmount(() => { requestId++; unregister() })
onMounted(() => { load(); routeDetail() })
</script>
<template><div><PageHeading title="预约管理" description="查看全院预约，跟进就诊状态与取药情况" /><section class="page-panel"><div class="table-toolbar"><div class="toolbar-left"><el-input v-model="filters.keyword" placeholder="搜索就诊人 / 手机号" :prefix-icon="Search" class="filter-input" clearable @keyup.enter="search" /><el-select v-model="filters.status" placeholder="全部状态" clearable @change="search"><el-option v-for="item in appointmentOptions" :key="item.value" :label="item.label" :value="item.value" /></el-select><el-date-picker v-model="filters.scheduleDate" type="date" value-format="YYYY-MM-DD" placeholder="就诊日期" @change="search" /><el-button type="primary" :icon="Search" @click="search">搜索</el-button><el-button :icon="Refresh" @click="reset">重置</el-button></div></div><el-alert v-if="error" :title="error" type="error" :closable="false" show-icon class="empty-error" /><el-table :data="rows" v-loading="loading"><el-table-column label="就诊人" prop="patientName" min-width="110" /><el-table-column label="手机号" prop="patientPhone" min-width="135" /><el-table-column label="科室" prop="departmentName" min-width="100" /><el-table-column label="医生" prop="doctorName" min-width="100" /><el-table-column label="就诊时间" min-width="165"><template #default="{ row }">{{ visitTime(row) }}</template></el-table-column><el-table-column label="挂号费" width="100"><template #default="{ row }">{{ money(row.amount) }}</template></el-table-column><el-table-column label="状态" width="110"><template #default="{ row }"><StatusTag :status="row.status" kind="appointment" :row="row" /></template></el-table-column><el-table-column label="操作" min-width="235" fixed="right"><template #default="{ row }"><div class="row-actions"><el-button type="primary" link @click="open(row)">详情</el-button><el-button v-if="canCancel(row)" type="danger" link :disabled="busy" @click="action(row, 'cancel')">取消</el-button><el-button v-if="canComplete(row)" type="success" link :disabled="busy" @click="action(row, 'complete')">标记完成</el-button><el-button v-if="canDispense(row)" type="warning" link :disabled="busy" @click="action(row, 'dispense')">标记取药</el-button></div></template></el-table-column></el-table><DataPagination v-model:page="page" v-model:size="size" :total="total" @change="load" /></section><el-drawer v-model="visible" title="预约详情" size="min(680px, 96vw)" :close-on-click-modal="!busy" :close-on-press-escape="!busy" :show-close="!busy" :before-close="done => { if (!busy) done() }"><div v-loading="detailLoading"><el-alert v-if="detailError" :title="detailError" type="error" :closable="false" /><template v-if="detail"><el-alert v-if="appointmentIssue(detail)" :title="appointmentIssue(detail)" type="warning" :closable="false" show-icon /><div class="appointment-detail-heading"><div><span class="muted text-small">预约编号 #{{ detail.id }}</span><h2>{{ detail.patientName }}</h2></div><StatusTag :status="detail.status" kind="appointment" :row="detail" /></div><el-descriptions :column="1" border><el-descriptions-item label="联系电话">{{ detail.patientPhone || '—' }}</el-descriptions-item><el-descriptions-item label="就诊科室">{{ detail.departmentName }}</el-descriptions-item><el-descriptions-item label="就诊医生">{{ detail.doctorName }}</el-descriptions-item><el-descriptions-item label="就诊时间">{{ visitTime(detail) }}</el-descriptions-item><el-descriptions-item label="身份证号">{{ detail.idCard || '未提供' }}</el-descriptions-item><el-descriptions-item label="挂号费用">{{ money(detail.amount) }}</el-descriptions-item><el-descriptions-item label="支付方式">{{ {balance:'余额支付',wechat:'模拟微信支付',alipay:'模拟支付宝支付'}[detail.payMethod] || '未支付' }}</el-descriptions-item><el-descriptions-item label="支付时间">{{ dateTime(detail.payTime) }}</el-descriptions-item><el-descriptions-item label="创建时间">{{ dateTime(detail.createTime) }}</el-descriptions-item><el-descriptions-item label="备注">{{ detail.remark || '无' }}</el-descriptions-item></el-descriptions><div v-if="detail.prescriptionId" class="prescription-overview"><h3>处方信息</h3><el-descriptions :column="1" border><el-descriptions-item label="处方编号">#{{ detail.prescriptionId }}</el-descriptions-item><el-descriptions-item label="处方金额">{{ money(detail.prescriptionAmount) }}</el-descriptions-item><el-descriptions-item label="处方状态"><StatusTag :status="detail.prescriptionStatus" kind="prescription" /></el-descriptions-item></el-descriptions></div><div class="detail-actions"><el-button v-if="canCancel(detail)" type="danger" plain :loading="busy" @click="action(detail, 'cancel')">取消预约</el-button><el-button v-if="canComplete(detail)" type="primary" :loading="busy" @click="action(detail, 'complete')">标记完成</el-button><el-button v-if="canDispense(detail)" type="warning" :loading="busy" @click="action(detail, 'dispense')">标记取药</el-button></div></template></div></el-drawer></div></template>
<style scoped>.appointment-detail-heading{display:flex;align-items:center;justify-content:space-between;margin-bottom:25px}.appointment-detail-heading h2{font-size:23px;font-weight:600;margin-top:10px;color:#263b56}.prescription-overview{margin-top:28px}.prescription-overview h3{font-size:15px;font-weight:600;margin-bottom:15px;color:#3a4d67}.detail-actions{display:flex;justify-content:flex-end;gap:9px;margin-top:28px}</style>
