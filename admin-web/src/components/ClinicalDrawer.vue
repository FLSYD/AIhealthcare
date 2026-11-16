<script setup>
import { computed, nextTick, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { onBeforeRouteLeave, onBeforeRouteUpdate } from 'vue-router'
import StatusTag from './StatusTag.vue'
import { api, confirmAction, runAction, TOKEN_KEY } from '../lib/api'
import { appointmentIssue, dateTime, money, visitTime } from '../lib/format'
import { registerNavigationGuard } from '../lib/navigation'
import { useStatusRefresh } from '../lib/useStatusRefresh'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  appointmentId: { type: [Number, String], default: null },
})
const emit = defineEmits(['update:modelValue', 'changed'])

const drawerVisible = computed({
  get: () => props.modelValue,
  set: value => emit('update:modelValue', value),
})

const appointmentKey = computed(() => {
  const raw = props.appointmentId
  if (raw === null || raw === undefined || raw === '') return ''
  const value = String(raw).trim()
  return /^[1-9]\d*$/.test(value) ? value : ''
})

const detail = ref(null)
const loading = ref(false)
const busy = ref(false)
const loadError = ref('')
const actionError = ref('')
const recordFormRef = ref(null)
const draftItems = ref([])
const medicineOptions = ref([])
const medicineLoading = ref(false)
const medicineError = ref('')
const medicineKeyword = ref('')
const selectedMedicineId = ref(null)

const recordFields = ['chiefComplaint', 'presentIllness', 'diagnosis', 'treatmentPlan', 'medicalAdvice']
const recordForm = reactive({
  chiefComplaint: '',
  presentIllness: '',
  diagnosis: '',
  treatmentPlan: '',
  medicalAdvice: '',
})
const recordSnapshot = ref({ ...recordForm })

let detailSequence = 0
let medicineSequence = 0
let medicineTimer = null

const appointment = computed(() => detail.value?.appointment || null)
const prescription = computed(() => detail.value?.prescription || null)
const statusIssue = computed(() => appointment.value ? appointmentIssue({
  ...appointment.value,
  prescriptionId: prescription.value?.id ?? appointment.value.prescriptionId,
  prescriptionStatus: prescription.value?.status ?? appointment.value.prescriptionStatus,
}) : '')
const items = computed(() => Array.isArray(detail.value?.items) ? detail.value.items : [])
const allowedActions = computed(() => new Set(
  Array.isArray(detail.value?.allowedActions)
    ? detail.value.allowedActions.filter(action => typeof action === 'string')
    : [],
))
const can = action => !loadError.value && !statusIssue.value && allowedActions.value.has(action)
const recordDisabled = computed(() => !can('saveRecord'))
const formControlsDisabled = computed(() => recordDisabled.value || busy.value || loading.value)
const recordDirty = computed(() => recordFields.some(field => (recordForm[field] || '') !== (recordSnapshot.value[field] || '')))
const prescriptionDraftDirty = computed(() => draftItems.value.length > 0)
const hasUnsavedChanges = computed(() => recordDirty.value || prescriptionDraftDirty.value)
const previewTotal = computed(() => draftItems.value.reduce((total, item) => {
  const unitPrice = Number(item.price || 0)
  const quantity = Number(item.quantity || 0)
  return total + (Number.isFinite(unitPrice) && Number.isFinite(quantity) ? unitPrice * quantity : 0)
}, 0))

const prescriptionNotice = computed(() => {
  if (!prescription.value) return ''
  if (prescription.value.status === 'unpaid') {
    return '等待患者在小程序完成缴费。缴费后处方将进入配药工作台，由配药师处理。'
  }
  if (prescription.value.status === 'paid') return '处方已缴费，等待配药师核对并发药。'
  if (prescription.value.status === 'dispensed') return '配药师已完成发药，处方仅供查看。'
  if (prescription.value.status === 'cancelled') return '处方已作废，本次仅可查看。'
  return ''
})
const stageNotice = computed(() => {
  if (!appointment.value) return ''
  if (statusIssue.value) return statusIssue.value
  return {
    unpaid: '挂号费尚未支付，待患者付款并在就诊当天签到后才能开始诊疗。',
    pending: '患者已支付挂号费，尚未到院签到。',
    confirmed: '预约已确认，等待患者在就诊当天到院签到。',
    checked_in: '患者已到院，请点击“开始诊疗”进入接诊。',
    in_consultation: '正在诊疗，请先保存病历，再开具处方或选择无药完成。',
    pending_rx_pay: '处方已提交，请等待患者完成缴费；缴费后由配药师处理。',
    pending_pickup: '处方已缴费，正在等待配药师核对并发药。',
    completed: '本次就诊已完成，病历和处方仅供查看。',
    cancelled: '预约已取消，不能继续接诊或开方。',
  }[appointment.value.status] || ''
})
const noPrescriptionText = computed(() => {
  if (statusIssue.value) return '处方信息异常，请联系管理员核对'
  if (appointment.value?.status === 'completed') return '本次就诊未开具处方'
  if (appointment.value?.status === 'cancelled') return '预约已取消，无可用处方'
  if (appointment.value?.status !== 'in_consultation') return '尚未开始诊疗，暂无处方'
  return '保存有效病历后，系统将开放处方入口'
})

const recordRules = {
  chiefComplaint: [{ trigger: 'blur', validator: textValidator('主诉', 500, true) }],
  presentIllness: [{ trigger: 'blur', validator: textValidator('现病史', 1000, false) }],
  diagnosis: [{ trigger: 'blur', validator: textValidator('诊断', 500, true) }],
  treatmentPlan: [{ trigger: 'blur', validator: textValidator('治疗方案', 1000, false) }],
  medicalAdvice: [{ trigger: 'blur', validator: textValidator('医嘱', 500, false) }],
}

function textLength(value) {
  return [...String(value || '')].length
}

function textValidator(label, limit, required) {
  return (_rule, value, callback) => {
    const text = String(value || '')
    if (required && !text.trim()) {
      callback(new Error(`${label}不能为空`))
      return
    }
    if (textLength(text) > limit) {
      callback(new Error(`${label}不能超过 ${limit} 个字符`))
      return
    }
    callback()
  }
}

function fillRecord(record) {
  const next = Object.fromEntries(recordFields.map(field => [field, record?.[field] || '']))
  Object.assign(recordForm, next)
  recordSnapshot.value = { ...next }
  nextTick(() => recordFormRef.value?.clearValidate())
}

function clearState() {
  detailSequence++
  medicineSequence++
  if (medicineTimer) clearTimeout(medicineTimer)
  medicineTimer = null
  detail.value = null
  loading.value = false
  busy.value = false
  loadError.value = ''
  actionError.value = ''
  draftItems.value = []
  medicineOptions.value = []
  medicineLoading.value = false
  medicineError.value = ''
  medicineKeyword.value = ''
  selectedMedicineId.value = null
  fillRecord(null)
}

async function loadDetail({ resetRecord = false, resetDraft = false, background = false, notifyParent = true } = {}) {
  const id = appointmentKey.value
  if (!props.modelValue || !id) return
  const current = ++detailSequence
  const previousId = detail.value?.appointment?.id === undefined ? '' : String(detail.value.appointment.id)
  if (!background) { loading.value = true; loadError.value = '' }
  if (resetDraft) draftItems.value = []
  try {
    const data = await api.get(`/doctor-workspace/consultations/${encodeURIComponent(id)}`, { quiet: background })
    if (current !== detailSequence || appointmentKey.value !== id || !props.modelValue || (background && busy.value)) return
    const preserveRecord = !resetRecord && recordDirty.value && previousId === id
    detail.value = data || null
    loadError.value = ''
    if (!preserveRecord) fillRecord(detail.value?.record)
    // 另一端已开方或结束诊疗时，不再保留可误提交的旧处方草稿。
    if (prescription.value || !can('prescribe')) draftItems.value = []
    if (!detail.value?.prescription && can('prescribe')) loadMedicines('')
    if (notifyParent) emit('changed')
  } catch (failure) {
    if (current === detailSequence) {
      loadError.value = background ? `状态同步失败，当前为上次结果，请刷新后操作：${failure.message}` : failure.message
      if (!detail.value) fillRecord(null)
    }
  } finally {
    if (current === detailSequence) loading.value = false
  }
}
useStatusRefresh(() => loadDetail({ background: true }), {
  enabled: () => props.modelValue && !!detail.value && !busy.value && !loading.value,
})

async function loadMedicines(keyword = '') {
  if (!props.modelValue || !appointmentKey.value) return
  const current = ++medicineSequence
  medicineLoading.value = true
  medicineError.value = ''
  try {
    const data = await api.get('/medicine/list', { params: { keyword: keyword || '' } })
    if (current !== medicineSequence || !props.modelValue) return
    medicineOptions.value = (Array.isArray(data) ? data : []).filter(item => Number(item.status) === 1)
  } catch (failure) {
    if (current === medicineSequence) {
      medicineOptions.value = []
      medicineError.value = failure.message
    }
  } finally {
    if (current === medicineSequence) medicineLoading.value = false
  }
}

function searchMedicines(keyword) {
  medicineKeyword.value = keyword || ''
  if (medicineTimer) clearTimeout(medicineTimer)
  medicineTimer = setTimeout(() => loadMedicines(medicineKeyword.value), 250)
}

function addMedicine() {
  if (busy.value) return
  actionError.value = ''
  const medicine = medicineOptions.value.find(item => String(item.id) === String(selectedMedicineId.value))
  if (!medicine) {
    actionError.value = '请选择有效的启用药品'
    return
  }
  if (draftItems.value.some(item => String(item.medicineId) === String(medicine.id))) {
    actionError.value = '处方中不能重复选择同一药品'
    return
  }
  if (draftItems.value.length >= 50) {
    actionError.value = '处方最多添加 50 项药品'
    return
  }
  draftItems.value.push({
    medicineId: medicine.id,
    medicineName: medicine.name,
    spec: medicine.spec || '',
    unit: medicine.unit || '',
    stock: Number(medicine.stock || 0),
    price: medicine.price,
    dosage: medicine.usageDesc || '',
    frequency: '',
    days: undefined,
    quantity: 1,
  })
  selectedMedicineId.value = null
}

function removeMedicine(index) {
  if (busy.value) return
  draftItems.value.splice(index, 1)
}

function validateDraftItems() {
  if (!can('prescribe')) return '当前服务端状态不允许开具处方'
  if (!draftItems.value.length) return '请至少添加一项药品'
  if (draftItems.value.length > 50) return '处方最多添加 50 项药品'
  const ids = new Set()
  for (const item of draftItems.value) {
    const id = String(item.medicineId || '')
    if (!id || ids.has(id)) return '处方药品不能为空或重复'
    ids.add(id)
    if (!String(item.dosage || '').trim()) return `请填写“${item.medicineName}”的用法用量`
    if (textLength(item.dosage) > 50) return `“${item.medicineName}”的用法用量不能超过 50 个字符`
    if (!String(item.frequency || '').trim()) return `请填写“${item.medicineName}”的用药频次`
    if (textLength(item.frequency) > 50) return `“${item.medicineName}”的用药频次不能超过 50 个字符`
    if (item.days !== undefined && item.days !== null && item.days !== ''
      && (!Number.isInteger(Number(item.days)) || Number(item.days) < 1 || Number(item.days) > 365)) {
      return '用药天数必须为 1 到 365 天或留空'
    }
    if (!Number.isInteger(Number(item.quantity)) || Number(item.quantity) < 1 || Number(item.quantity) > 1000) {
      return '药品数量必须为 1 到 1000'
    }
  }
  return ''
}

async function saveRecord() {
  if (busy.value || loading.value || !can('saveRecord') || !recordFormRef.value) return
  actionError.value = ''
  let valid = false
  try { valid = await recordFormRef.value.validate() } catch { valid = false }
  if (!valid) return
  const id = appointmentKey.value
  const payload = Object.fromEntries(recordFields.map(field => [field, recordForm[field] || '']))
  busy.value = true
  try {
    const result = await runAction(
      () => api.put(`/doctor-workspace/consultations/${encodeURIComponent(id)}/record`, payload),
      '病历已保存',
    )
    if (result.ok && appointmentKey.value === id) {
      fillRecord(result.data && typeof result.data === 'object' ? result.data : payload)
      await loadDetail({ notifyParent: false })
      emit('changed')
    }
  } finally {
    busy.value = false
  }
}

async function submitPrescription() {
  if (busy.value || loading.value || !can('prescribe')) return
  actionError.value = ''
  if (recordDirty.value) {
    actionError.value = '病历有尚未保存的修改，请先保存病历再开具处方'
    return
  }
  const validation = validateDraftItems()
  if (validation) {
    actionError.value = validation
    return
  }
  const id = appointmentKey.value
  const payload = {
    items: draftItems.value.map(item => {
      const output = {
        medicineId: item.medicineId,
        dosage: String(item.dosage).trim(),
        frequency: String(item.frequency).trim(),
        quantity: Number(item.quantity),
      }
      if (item.days !== undefined && item.days !== null && item.days !== '') output.days = Number(item.days)
      return output
    }),
  }
  busy.value = true
  try {
    const result = await runAction(
      () => api.post(`/doctor-workspace/consultations/${encodeURIComponent(id)}/prescription`, payload),
      '处方已提交',
    )
    if (result.ok && appointmentKey.value === id) {
      draftItems.value = []
      await loadDetail({ resetRecord: true, resetDraft: true, notifyParent: false })
      emit('changed')
    }
  } finally {
    busy.value = false
  }
}

async function performAction(action, successMessage, confirmation = '') {
  if (busy.value || loading.value || !can(action)) return
  const id = appointmentKey.value
  const generation = detailSequence
  actionError.value = ''
  if (recordDirty.value && action !== 'start') {
    actionError.value = '病历有尚未保存的修改，请先保存病历再继续'
    return
  }
  if (action === 'complete' && prescriptionDraftDirty.value) {
    actionError.value = '处方草稿中已有药品，请开具处方或移除药品后再选择无药完成'
    return
  }
  busy.value = true
  try {
    if (confirmation && !await confirmAction(confirmation)) return
    if (id !== appointmentKey.value || generation !== detailSequence || !props.modelValue || !can(action)) return
    const result = await runAction(
      () => api.put(`/doctor-workspace/consultations/${encodeURIComponent(id)}/${action}`),
      successMessage,
    )
    if (result.ok && appointmentKey.value === id && generation === detailSequence) {
      await loadDetail({ resetRecord: true, resetDraft: true, notifyParent: false })
      emit('changed')
    }
  } finally {
    busy.value = false
  }
}

function startConsultation() {
  return performAction('start', '已开始诊疗')
}

function completeConsultation() {
  return performAction('complete', '诊疗已完成', '确认本次诊疗不开具处方，并将预约标记为完成吗？')
}

async function beforeClose(done) {
  if (busy.value) return
  if (hasUnsavedChanges.value && !await confirmAction('当前病历或处方草稿尚未提交，关闭后将丢失。仍要关闭吗？', '关闭诊疗抽屉')) return
  detailSequence++
  medicineSequence++
  done()
}

async function allowLeave() {
  if (!props.modelValue) return true
  if (busy.value) { ElMessage.warning('当前操作尚未完成，请稍候'); return false }
  return !hasUnsavedChanges.value || await confirmAction('病历或处方草稿尚未保存，离开后将丢失。确定离开吗？', '离开诊疗页面')
}
onBeforeRouteLeave(to => to.path === '/login' && !localStorage.getItem(TOKEN_KEY) ? true : allowLeave())
onBeforeRouteUpdate((to, from) => to.query.id === from.query.id ? true : allowLeave())
const unregisterNavigationGuard = registerNavigationGuard(allowLeave)
function protectUnload(event) {
  if (props.modelValue && (hasUnsavedChanges.value || busy.value)) {
    event.preventDefault()
    event.returnValue = ''
  }
}
window.addEventListener('beforeunload', protectUnload)

function closeDrawer() {
  if (busy.value) return
  if (hasUnsavedChanges.value) {
    confirmAction('当前病历或处方草稿尚未提交，关闭后将丢失。仍要关闭吗？', '关闭诊疗抽屉').then(confirmed => {
      if (confirmed) drawerVisible.value = false
    })
    return
  }
  drawerVisible.value = false
}

watch(
  () => [props.modelValue, appointmentKey.value],
  ([open, id]) => {
    clearState()
    if (open && id) loadDetail()
  },
  { immediate: true },
)

onBeforeUnmount(() => {
  window.removeEventListener('beforeunload', protectUnload)
  unregisterNavigationGuard()
  detailSequence++
  medicineSequence++
  if (medicineTimer) clearTimeout(medicineTimer)
})
</script>

<template>
  <el-drawer
    v-model="drawerVisible"
    size="min(920px, 95vw)"
    :before-close="beforeClose"
    :close-on-click-modal="!busy"
    :close-on-press-escape="!busy"
    append-to-body
    class="clinical-drawer"
  >
    <template #header>
      <div class="drawer-title">
        <div>
          <h2>诊疗详情</h2>
          <p v-if="appointment">
            {{ appointment.patientName || '未设置就诊人' }}
            <span v-if="appointment.patientPhone"> · {{ appointment.patientPhone }}</span>
            <span> · {{ visitTime(appointment) }}</span>
          </p>
        </div>
        <StatusTag v-if="appointment" :status="appointment.status" :row="appointment" kind="appointment" />
      </div>
    </template>

    <div v-loading="loading" class="drawer-body-scroll">
      <el-alert v-if="loadError" :title="loadError" type="error" show-icon :closable="false">
        <template #default><el-button link type="primary" @click="loadDetail()">重新加载</el-button></template>
      </el-alert>
      <el-alert v-if="actionError" :title="actionError" type="warning" show-icon :closable="false" />
      <el-alert v-if="stageNotice" :title="stageNotice" :type="statusIssue ? 'warning' : 'info'" show-icon :closable="false" />
      <el-alert v-if="recordDirty && !can('saveRecord')" title="当前输入中仍有未保存的病历草稿，尚未写入病历。请复制需要保留的内容，再关闭并重新查看。" type="warning" show-icon :closable="false" />

      <template v-if="detail">
        <section class="clinical-section visit-summary">
          <div class="summary-item"><span>就诊人</span><strong>{{ appointment?.patientName || '—' }}</strong></div>
          <div class="summary-item"><span>联系电话</span><strong>{{ appointment?.patientPhone || '—' }}</strong></div>
          <div class="summary-item"><span>就诊时间</span><strong>{{ visitTime(appointment) }}</strong></div>
          <div class="summary-item"><span>科室</span><strong>{{ appointment?.departmentName || '—' }}</strong></div>
        </section>

        <section class="clinical-section">
          <div class="section-heading">
            <div>
              <h3>病历</h3>
              <p>主诉与诊断必填，请完整记录本次诊疗情况。</p>
            </div>
            <el-tag v-if="detail.record?.updateTime" type="info" effect="plain">更新于 {{ dateTime(detail.record.updateTime) }}</el-tag>
          </div>
          <el-form ref="recordFormRef" :model="recordForm" :rules="recordRules" label-position="top" class="record-form">
            <el-form-item label="主诉" prop="chiefComplaint">
              <el-input v-model="recordForm.chiefComplaint" type="textarea" :rows="3" maxlength="500" show-word-limit :disabled="formControlsDisabled" placeholder="请输入患者本次就诊的主要症状及持续时间" />
            </el-form-item>
            <el-form-item label="现病史" prop="presentIllness">
              <el-input v-model="recordForm.presentIllness" type="textarea" :rows="4" maxlength="1000" show-word-limit :disabled="formControlsDisabled" placeholder="选填，请记录疾病发生、发展及诊疗经过" />
            </el-form-item>
            <el-form-item label="诊断" prop="diagnosis">
              <el-input v-model="recordForm.diagnosis" type="textarea" :rows="3" maxlength="500" show-word-limit :disabled="formControlsDisabled" placeholder="请输入明确诊断" />
            </el-form-item>
            <el-form-item label="治疗方案" prop="treatmentPlan">
              <el-input v-model="recordForm.treatmentPlan" type="textarea" :rows="4" maxlength="1000" show-word-limit :disabled="formControlsDisabled" placeholder="选填，请记录治疗安排" />
            </el-form-item>
            <el-form-item label="医嘱" prop="medicalAdvice">
              <el-input v-model="recordForm.medicalAdvice" type="textarea" :rows="3" maxlength="500" show-word-limit :disabled="formControlsDisabled" placeholder="选填，请记录复诊及注意事项" />
            </el-form-item>
          </el-form>
        </section>

        <section class="clinical-section prescription-section">
          <div class="section-heading">
            <div>
              <h3>处方</h3>
              <p v-if="!prescription">开方前必须先保存包含主诉和诊断的有效病历。</p>
              <p v-else>请核对处方药品、用法用量与缴费状态。</p>
            </div>
            <div v-if="prescription" class="prescription-meta">
              <StatusTag :status="prescription.status" kind="prescription" />
              <strong>{{ money(prescription.totalAmount) }}</strong>
            </div>
          </div>

          <el-alert v-if="prescriptionNotice" :title="prescriptionNotice" type="info" show-icon :closable="false" />

          <div v-if="prescription">
            <div class="prescription-details">
              <span>开方时间：{{ dateTime(prescription.createTime) }}</span>
              <span v-if="prescription.payTime">缴费时间：{{ dateTime(prescription.payTime) }}</span>
            </div>
            <div class="table-scroll-x">
              <el-table :data="items" row-key="id" border>
                <el-table-column prop="medicineName" label="药品" min-width="150" show-overflow-tooltip />
                <el-table-column prop="spec" label="规格" min-width="130" show-overflow-tooltip />
                <el-table-column prop="dosage" label="用法用量" min-width="150" show-overflow-tooltip />
                <el-table-column prop="frequency" label="频次" min-width="110" show-overflow-tooltip />
                <el-table-column prop="days" label="天数" width="80" align="center" />
                <el-table-column prop="quantity" label="数量" width="80" align="center" />
                <el-table-column label="单价" width="105" align="right">
                  <template #default="{ row }">{{ money(row.unitPrice) }}</template>
                </el-table-column>
                <el-table-column label="金额" width="110" align="right">
                  <template #default="{ row }">{{ money(row.amount) }}</template>
                </el-table-column>
                <template #empty><el-empty description="处方暂无药品明细" :image-size="72" /></template>
              </el-table>
            </div>
          </div>

          <div v-else-if="can('prescribe')" class="prescription-editor">
            <div class="medicine-picker">
              <el-select
                v-model="selectedMedicineId"
                filterable
                remote
                clearable
                reserve-keyword
                remote-show-suffix
                :remote-method="searchMedicines"
                :loading="medicineLoading"
                :disabled="busy"
                placeholder="搜索启用中的药品"
                class="medicine-select"
              >
                <el-option
                  v-for="medicine in medicineOptions"
                  :key="medicine.id"
                  :label="`${medicine.name}${medicine.spec ? ` · ${medicine.spec}` : ''}`"
                  :value="medicine.id"
                  :disabled="Number(medicine.stock || 0) < 1"
                >
                  <div class="medicine-option">
                    <span>{{ medicine.name }}<small v-if="medicine.spec"> · {{ medicine.spec }}</small></span>
                    <span>{{ money(medicine.price) }} · 库存 {{ Number(medicine.stock || 0) }}{{ medicine.unit || '' }}</span>
                  </div>
                </el-option>
              </el-select>
              <el-button type="primary" plain :disabled="!selectedMedicineId || busy" @click="addMedicine">添加药品</el-button>
            </div>
            <el-alert v-if="medicineError" :title="medicineError" type="error" show-icon :closable="false" />

            <div v-if="draftItems.length" class="table-scroll-x">
              <el-table :data="draftItems" row-key="medicineId" border>
                <el-table-column label="药品" min-width="150">
                  <template #default="{ row }">
                    <strong>{{ row.medicineName }}</strong>
                    <div class="muted">{{ row.spec || '未设置规格' }} · 库存 {{ row.stock }}</div>
                  </template>
                </el-table-column>
                <el-table-column label="用法用量" min-width="190">
                  <template #default="{ row }"><el-input v-model="row.dosage" maxlength="50" :disabled="busy" placeholder="必填，最多 50 字" /></template>
                </el-table-column>
                <el-table-column label="用药频次" min-width="160">
                  <template #default="{ row }"><el-input v-model="row.frequency" maxlength="50" :disabled="busy" placeholder="必填，最多 50 字" /></template>
                </el-table-column>
                <el-table-column label="天数" width="120" align="center">
                  <template #default="{ row }"><el-input-number v-model="row.days" :min="1" :max="365" :disabled="busy" controls-position="right" placeholder="可选" /></template>
                </el-table-column>
                <el-table-column label="数量" width="130" align="center">
                  <template #default="{ row }"><el-input-number v-model="row.quantity" :min="1" :max="Math.min(1000, Math.max(1, row.stock))" :disabled="busy" controls-position="right" /></template>
                </el-table-column>
                <el-table-column label="金额预览" width="115" align="right">
                  <template #default="{ row }">{{ money(Number(row.price || 0) * Number(row.quantity || 0)) }}</template>
                </el-table-column>
                <el-table-column label="操作" width="78" align="center">
                  <template #default="{ $index }"><el-button link type="danger" :disabled="busy" @click="removeMedicine($index)">移除</el-button></template>
                </el-table-column>
                <template #empty><el-empty description="尚未添加药品" :image-size="72" /></template>
              </el-table>
            </div>
            <div class="price-preview">
              <span>费用预览，实际金额以提交后的处方为准</span>
              <strong>{{ money(previewTotal) }}</strong>
            </div>
          </div>

          <el-empty v-else :description="noPrescriptionText" :image-size="76" />
        </section>
      </template>

      <el-empty v-else-if="!loading && !loadError" description="未找到该预约的诊疗详情">
        <el-button type="primary" @click="loadDetail()">重新加载</el-button>
      </el-empty>
    </div>

    <template #footer>
      <div class="drawer-footer">
        <span class="footer-note">{{ busy ? '正在处理，请稍候' : loadError ? '状态未同步，请先刷新' : stageNotice || prescriptionNotice }}</span>
        <div class="footer-actions">
          <el-button :icon="Refresh" :loading="loading" :disabled="busy" @click="loadDetail()">刷新状态</el-button>
          <el-button v-if="can('start')" type="primary" :loading="busy" :disabled="loading" @click="startConsultation">开始诊疗</el-button>
          <el-button v-if="can('saveRecord')" type="primary" plain :loading="busy" :disabled="loading" @click="saveRecord">保存病历</el-button>
          <el-button v-if="can('prescribe')" type="primary" :loading="busy" :disabled="loading" @click="submitPrescription">开具处方</el-button>
          <el-button v-if="can('complete')" type="success" :loading="busy" :disabled="loading" @click="completeConsultation">无药完成</el-button>
          <el-button :disabled="busy" @click="closeDrawer">关闭</el-button>
        </div>
      </div>
    </template>
  </el-drawer>
</template>

<style scoped>
:deep(.el-drawer__header) { margin-bottom: 0; padding: 19px 22px 16px; border-bottom: 1px solid #e7edf5; }
:deep(.el-drawer__body), :global(.clinical-drawer .el-drawer__body) { min-height: 0; padding: 0 !important; overflow: hidden; }
:deep(.el-drawer__footer) { position: sticky; bottom: 0; z-index: 3; padding: 13px 20px; border-top: 1px solid #e7edf5; background: rgba(255, 255, 255, .98); box-shadow: 0 -8px 22px rgba(45, 78, 120, .06); }
.drawer-title { display: flex; align-items: center; justify-content: space-between; gap: 14px; width: 100%; padding-right: 10px; }
.drawer-title h2 { margin: 0; color: #1b2a40; font-size: 20px; }
.drawer-title p { margin: 5px 0 0; color: #738095; font-size: 13px; }
.drawer-body-scroll { height: 100%; min-height: 360px; padding: 20px; overflow-y: auto; background: #f5f8fc; }
.drawer-body-scroll > .el-alert + .el-alert, .drawer-body-scroll > .el-alert + .clinical-section { margin-top: 12px; }
.clinical-section { margin-bottom: 16px; padding: 20px; border: 1px solid #e6edf6; border-radius: 14px; background: #fff; }
.visit-summary { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 14px; }
.summary-item { min-width: 0; padding: 12px 14px; border-radius: 10px; background: #f7faff; }
.summary-item span, .summary-item strong { display: block; }
.summary-item span { margin-bottom: 5px; color: #8995a4; font-size: 12px; }
.summary-item strong { overflow: hidden; color: #26384f; font-size: 14px; text-overflow: ellipsis; white-space: nowrap; }
.section-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin-bottom: 16px; }
.section-heading h3 { margin: 0; color: #1f3047; font-size: 17px; }
.section-heading p { margin: 5px 0 0; color: #8a96a6; font-size: 12px; }
.record-form { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 16px; }
.record-form :deep(.el-form-item:first-child), .record-form :deep(.el-form-item:nth-child(3)), .record-form :deep(.el-form-item:last-child) { grid-column: 1 / -1; }
.prescription-meta { display: flex; align-items: center; gap: 10px; }
.prescription-meta strong { color: #1f6fd1; font-size: 18px; }
.prescription-details { display: flex; flex-wrap: wrap; gap: 8px 20px; margin: 14px 0 12px; color: #7b8797; font-size: 12px; }
.table-scroll-x { width: 100%; overflow-x: auto; }
.table-scroll-x :deep(.el-table) { min-width: 760px; }
.prescription-editor { margin-top: 14px; }
.medicine-picker { display: flex; align-items: center; gap: 10px; margin-bottom: 12px; }
.medicine-select { width: min(440px, 100%); }
.medicine-option { display: flex; justify-content: space-between; gap: 24px; }
.medicine-option small { color: #8a96a6; }
.price-preview { display: flex; align-items: center; justify-content: flex-end; gap: 18px; margin-top: 12px; color: #8895a5; font-size: 12px; }
.price-preview strong { color: #d97706; font-size: 19px; }
.drawer-footer { display: flex; align-items: center; justify-content: space-between; gap: 14px; }
.footer-note { color: #8a96a6; font-size: 12px; }
.footer-actions { display: flex; flex-wrap: wrap; justify-content: flex-end; gap: 8px 0; }
.footer-actions :deep(.el-button + .el-button) { margin-left: 8px; }
@media (max-width: 760px) {
  :deep(.el-drawer__header) { padding: 16px; }
  :deep(.el-drawer__footer) { padding: 11px 12px; }
  .drawer-title { align-items: flex-start; }
  .drawer-title p { line-height: 1.55; }
  .drawer-body-scroll { padding: 12px; }
  .clinical-section { padding: 15px; border-radius: 12px; }
  .visit-summary { grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px; }
  .section-heading { flex-direction: column; gap: 8px; }
  .record-form { grid-template-columns: 1fr; }
  .record-form :deep(.el-form-item), .record-form :deep(.el-form-item:first-child), .record-form :deep(.el-form-item:nth-child(3)), .record-form :deep(.el-form-item:last-child) { grid-column: auto; }
  .medicine-picker { align-items: stretch; flex-direction: column; }
  .medicine-select { width: 100%; }
  .price-preview { align-items: flex-end; flex-direction: column; gap: 3px; }
  .drawer-footer { align-items: stretch; flex-direction: column; }
  .footer-note { display: none; }
  .footer-actions { justify-content: flex-end; }
}
</style>
