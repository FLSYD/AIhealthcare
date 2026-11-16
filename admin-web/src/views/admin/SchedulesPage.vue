<script setup>
import { computed, nextTick, onMounted, reactive, ref } from 'vue'
import { Delete, Edit, Plus, Refresh, Search } from '@element-plus/icons-vue'
import PageHeading from '../../components/PageHeading.vue'
import DataPagination from '../../components/DataPagination.vue'
import StatusTag from '../../components/StatusTag.vue'
import { api, confirmAction, loadAll, runAction } from '../../lib/api'
import { usePagedList } from '../../lib/usePagedList'
import { dateTime } from '../../lib/format'
import { hospitalToday, scheduleQuery } from '../../lib/scheduleFilters'
import { useStatusRefresh } from '../../lib/useStatusRefresh'

const includeHistory = ref(false)
const dateFilter = ref('')
const appliedDate = ref('')
const appliedHistory = ref(false)
const { filters, rows, total, page, size, loading, error, load, search, reset } =
  usePagedList('/schedule/page', { doctorId: null }, {
    fetchPage: (params, options) => {
      const useDate = appliedDate.value
      const useHistory = appliedHistory.value
      if (!useHistory && useDate && useDate < hospitalToday()) {
        appliedDate.value = ''
        dateFilter.value = ''
      }
      return api.get('/schedule/page', { ...options,
        params: scheduleQuery(params, { includeHistory: useHistory, date: useDate }),
      })
    },
  })
const doctors = ref([])
const doctorsLoading = ref(false)
const doctorError = ref('')
const formRef = ref()
const dialogVisible = ref(false)
const busy = ref(false)
let formSequence = 0
const form = reactive(defaultForm())
const batchVisible = ref(false)
const batchBusy = ref(false)
const batchRef = ref()
const batchResult = ref(null)
const batchForm = reactive({ doctorId: null, dates: [], timeSlots: [], totalQuota: 20, status: 1 })
const batchCount = computed(() => new Set(batchForm.dates || []).size * new Set((batchForm.timeSlots || []).map(value => value.trim())).size)
const TIME_SLOT_PATTERN = /^([01]\d|2[0-3]):[0-5]\d$/
const isValidTimeSlot = value => {
  const slot = typeof value === 'string' ? value.trim() : ''
  return slot === '上午' || slot === '下午' || TIME_SLOT_PATTERN.test(slot)
}
const batchRules = {
  doctorId: [{ required: true, message: '请选择出诊医生', trigger: 'change' }],
  dates: [{ validator: (_, value, done) => done(Array.isArray(value) && value.length >= 1 && value.length <= 5 ? undefined : new Error('请选择1至5个出诊日期')), trigger: 'change' }],
  timeSlots: [{ validator: (_, value, done) => {
    const slots = Array.isArray(value) ? value.map(slot => typeof slot === 'string' ? slot.trim() : '') : []
    if (!slots.length || slots.length > 6) return done(new Error('请选择1至6个时段'))
    if (slots.some(slot => !slot || [...slot].length > 20)) return done(new Error('每个时段不能为空且不能超过20个字符'))
    return done(slots.every(isValidTimeSlot) ? undefined : new Error('时段必须为 HH:mm、上午或下午；HH 为 00-23，mm 为 00-59'))
  }, trigger: 'change' }],
  totalQuota: [{ validator: (_, value, done) => done(Number.isInteger(value) && value > 0 && value <= 999999 ? undefined : new Error('每个时段号源须为1至999999的整数')), trigger: 'change' }],
}
const identityLocked = computed(() => Boolean(form.id && totalHistory(form) > 0))
const dialogTitle = computed(() => form.id ? '编辑排班' : '新增排班')
const timeOptions = ['上午', '下午', '08:00', '09:00', '10:00', '14:00', '15:00', '16:00']
const rules = {
  doctorId: [{ required: true, message: '请选择医生', trigger: 'change' }],
  scheduleDate: [
    { required: true, message: '请选择排班日期', trigger: 'change' },
    { validator: validateScheduleDate, trigger: 'change' },
  ],
  timeSlot: [{ validator: validateTimeSlot, trigger: ['blur', 'change'] }],
  totalQuota: [{ validator: validateQuota, trigger: 'change' }],
  status: [{ required: true, message: '请选择状态', trigger: 'change' }],
}

function defaultForm() {
  return {
    id: null, doctorId: null, scheduleDate: '', timeSlot: '',
    totalQuota: 20, bookedCount: 0, appointmentCount: null, status: 1, originalScheduleDate: '',
  }
}
function totalHistory(row) {
  const v = row?.appointmentCount != null ? row.appointmentCount : row?.bookedCount
  return Number(v) || 0
}
function validateQuota(rule, value, callback) {
  if (value == null) return callback(new Error('请输入总号源'))
  if (!Number.isInteger(value) || value < Math.max(1, Number(form.bookedCount))) {
    return callback(new Error(`总号源必须为不小于 ${Math.max(1, Number(form.bookedCount))} 的整数`))
  }
  if (value > 999999) return callback(new Error('总号源不能超过 999999'))
  callback()
}
function validateScheduleDate(rule, value, callback) {
  if (!value) return callback(new Error('请选择排班日期'))
  if (value < hospitalToday() && !(form.id && value === form.originalScheduleDate)) {
    return callback(new Error('出诊日期不能早于今天'))
  }
  callback()
}
function validateTimeSlot(rule, value, callback) {
  const slot = typeof value === 'string' ? value.trim() : ''
  if (!slot) return callback(new Error('请输入时间段'))
  if ([...slot].length > 20) return callback(new Error('时间段不能超过 20 个字符'))
  if (!isValidTimeSlot(slot)) {
    return callback(new Error('时间段必须为 HH:mm、上午或下午；HH 为 00-23，mm 为 00-59'))
  }
  callback()
}
async function loadDoctors() {
  doctorsLoading.value = true
  doctorError.value = ''
  try { doctors.value = (await loadAll('/doctor/page')).filter(item => item.roleType !== 'PHARMACIST') }
  catch (failure) { doctorError.value = failure.message }
  finally { doctorsLoading.value = false }
}
function resetForm() {
  formSequence++
  Object.assign(form, defaultForm())
  nextTick(() => formRef.value?.clearValidate())
}
function openCreate() {
  if (busy.value || batchBusy.value) return
  resetForm()
  dialogVisible.value = true
}
function openBatch() {
  if (busy.value || batchBusy.value) return
  Object.assign(batchForm, { doctorId: null, dates: [], timeSlots: [], totalQuota: 20, status: 1 })
  batchResult.value = null
  batchVisible.value = true
  nextTick(() => batchRef.value?.clearValidate())
}
function datePickerValue(date) {
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`
}
function isPastDate(date) {
  return datePickerValue(date) < hospitalToday()
}
function singleDateDisabled(date) {
  const value = datePickerValue(date)
  return value < hospitalToday() && !(form.id && value === form.originalScheduleDate)
}
async function submitBatch() {
  if (batchBusy.value || batchResult.value) return
  try { await batchRef.value.validate() } catch { return }
  const payload = {
    doctorId: batchForm.doctorId,
    dates: [...new Set(batchForm.dates)].sort(),
    timeSlots: [...new Set(batchForm.timeSlots.map(value => value.trim()))],
    totalQuota: batchForm.totalQuota,
    status: batchForm.status,
  }
  batchBusy.value = true
  try {
    const result = await runAction(() => api.post('/schedule/batch', payload), '')
    if (!result.ok) return
    batchResult.value = result.data
    await load()
  } finally { batchBusy.value = false }
}
function openEdit(row) {
  if (busy.value) return
  formSequence++
  const scheduleDate = String(row.scheduleDate || '').slice(0, 10)
  Object.assign(form, {
    id: row.id,
    doctorId: row.doctorId,
    scheduleDate,
    timeSlot: row.timeSlot || '',
    totalQuota: Number(row.totalQuota || 0),
    bookedCount: Number(row.bookedCount || 0),
    appointmentCount: row.appointmentCount == null ? null : Number(row.appointmentCount),
    status: Number(row.status) === 1 ? 1 : 0,
    originalScheduleDate: scheduleDate,
  })
  dialogVisible.value = true
  nextTick(() => formRef.value?.clearValidate())
}
function doctorLabel(row) {
  return row.doctorName || doctors.value.find(item => item.id === row.doctorId)?.name || '—'
}
function doctorOptionDisabled(item) {
  return Number(item.status) !== 1
}
async function searchSchedules() { commitExternal(); await search() }
async function changeHistory() {
  if (!includeHistory.value && dateFilter.value < hospitalToday()) dateFilter.value = ''
  commitExternal()
  await search()
}
function dateDisabled(date) {
  if (includeHistory.value) return false
  const value = `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`
  return value < hospitalToday()
}
async function resetSchedules() {
  dateFilter.value = ''
  includeHistory.value = false
  commitExternal()
  await reset()
}
function commitExternal() {
  appliedDate.value = dateFilter.value
  appliedHistory.value = includeHistory.value
}
// 页面跨过零点或重新获得焦点时，按新的当天日期重新查询。
useStatusRefresh(() => load({ background: true }), {
  interval: 60000,
  enabled: () => !loading.value && !busy.value && !batchBusy.value && !dialogVisible.value && !batchVisible.value,
})

async function submit() {
  if (busy.value) return
  try { await formRef.value.validate() } catch { return }
  const editing = Boolean(form.id)
  const sequence = formSequence
  const payload = {
    ...(editing ? { id: form.id } : {}),
    doctorId: form.doctorId,
    scheduleDate: form.scheduleDate,
    timeSlot: form.timeSlot.trim(),
    totalQuota: form.totalQuota,
    status: form.status,
  }
  busy.value = true
  const result = await runAction(
    () => editing ? api.put('/schedule', payload) : api.post('/schedule', payload),
    editing ? '排班信息已更新' : '排班已新增',
  )
  busy.value = false
  if (!result.ok) return
  if (sequence !== formSequence || !dialogVisible.value) { await load(); return }
  dialogVisible.value = false
  await load()
}

async function changeStatus(row, enabled) {
  if (busy.value) return
  const status = enabled ? 1 : 0
  if (!status && !await confirmAction(`确定停用 ${row.scheduleDate} ${row.timeSlot} 的排班吗？停用后不再开放预约。`, '停用排班')) return
  busy.value = true
  const result = await runAction(
    () => api.put(`/schedule/${row.id}/status/${status}`),
    status ? '排班已开放' : '排班已停诊',
  )
  busy.value = false
  if (result.ok) await load()
}

async function remove(row) {
  if (busy.value || totalHistory(row) > 0) return
  const confirmed = await confirmAction(
    `确定删除 ${row.doctorName || '该医生'} 在 ${row.scheduleDate} ${row.timeSlot} 的排班吗？`,
    '删除排班',
  )
  if (!confirmed) return
  busy.value = true
  const result = await runAction(() => api.delete(`/schedule/${row.id}`), '排班已删除')
  busy.value = false
  if (result.ok) await load()
}

onMounted(() => {
  commitExternal()
  load()
  loadDoctors()
})
</script>

<template>
  <div class="page-stack">
    <PageHeading title="排班管理" description="默认显示今天及以后的排班，勾选历史选项可查看过往记录">
      <el-button :icon="Plus" @click="openBatch">批量新增</el-button>
      <el-button type="primary" :icon="Plus" @click="openCreate">新增排班</el-button>
    </PageHeading>

    <section class="page-panel">
      <el-form class="table-toolbar" inline @submit.prevent="searchSchedules">
        <div class="toolbar-left">
          <el-form-item label="医生">
            <el-select v-model="filters.doctorId" :loading="doctorsLoading" clearable filterable placeholder="全部医生" style="width: 180px">
              <el-option v-for="item in doctors" :key="item.id" :label="`${item.name} · ${item.departmentName || '未分配科室'}`" :value="item.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="出诊日期">
            <el-date-picker v-model="dateFilter" type="date" value-format="YYYY-MM-DD" :disabled-date="dateDisabled" placeholder="选择日期" clearable style="width: 160px" />
          </el-form-item>
          <el-checkbox v-model="includeHistory" :disabled="loading" @change="changeHistory">显示历史排班</el-checkbox>
          <el-button type="primary" :icon="Search" :disabled="loading" @click="searchSchedules">查询</el-button>
          <el-button :icon="Refresh" :disabled="loading" @click="resetSchedules">重置</el-button>
        </div>
      </el-form>
      <el-alert v-if="doctorError" class="inline-alert" type="warning" :closable="false" show-icon>
        <template #title>医生候选加载失败：{{ doctorError }}</template>
        <el-button link type="primary" :loading="doctorsLoading" @click="loadDoctors">重新加载</el-button>
      </el-alert>

      <el-table v-loading="loading" :data="rows" row-key="id">
        <el-table-column label="医生" min-width="170">
          <template #default="{ row }"><strong>{{ doctorLabel(row) }}</strong><div class="muted">{{ row.title || '未填写职称' }}</div></template>
        </el-table-column>
        <el-table-column label="科室" min-width="130">
          <template #default="{ row }">{{ row.departmentName || '—' }}</template>
        </el-table-column>
        <el-table-column prop="scheduleDate" label="出诊日期" width="130" />
        <el-table-column prop="timeSlot" label="时间段" min-width="140" />
        <el-table-column label="预约/号源" width="130" align="center">
          <template #default="{ row }"><strong>{{ Number(row.bookedCount || 0) }}</strong><span class="muted"> / {{ row.totalQuota }}</span></template>
        </el-table-column>
        <el-table-column label="状态" width="100" align="center">
          <template #default="{ row }"><StatusTag :status="row.status" kind="schedule" /></template>
        </el-table-column>
        <el-table-column label="创建时间" width="170">
          <template #default="{ row }">{{ dateTime(row.createTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="235" fixed="right">
          <template #default="{ row }">
            <div class="row-actions">
              <el-button link type="primary" :icon="Edit" :disabled="busy" @click="openEdit(row)">编辑</el-button>
              <el-button link :type="Number(row.status) === 1 ? 'warning' : 'success'" :disabled="busy" @click="changeStatus(row, Number(row.status) !== 1)">
                {{ Number(row.status) === 1 ? '停诊' : '开放' }}
              </el-button>
              <el-tooltip v-if="totalHistory(row) > 0" placement="top" content="已有历史预约（含已取消），无法删除；如需暂停预约可点停诊">
                <span><el-button link type="danger" :icon="Delete" disabled>删除</el-button></span>
              </el-tooltip>
              <el-button v-else link type="danger" :icon="Delete" :disabled="busy" @click="remove(row)">删除</el-button>
            </div>
          </template>
        </el-table-column>
        <template #empty>
          <div class="empty-state">
            <p>{{ error ? '排班数据加载失败' : '暂无排班数据' }}</p>
            <span v-if="error" class="muted">{{ error }}</span>
            <el-button v-if="error" type="primary" plain @click="load">重新加载</el-button>
          </div>
        </template>
      </el-table>

      <DataPagination v-model:page="page" v-model:size="size" :total="total" @change="load" />
    </section>

    <el-dialog v-model="batchVisible" title="批量新增排班" width="650px" destroy-on-close :close-on-click-modal="!batchBusy" :close-on-press-escape="!batchBusy" :show-close="!batchBusy" :before-close="done => { if (!batchBusy) done() }">
      <el-alert title="最多选择5个日期、6个时段，每个日期与时段组合生成一条排班。已存在或已过期的组合会自动跳过。" type="info" :closable="false" show-icon class="inline-alert" />
      <el-form v-if="!batchResult" ref="batchRef" :model="batchForm" :rules="batchRules" :disabled="batchBusy" label-position="top" @submit.prevent="submitBatch">
        <el-form-item label="出诊医生" prop="doctorId"><el-select v-model="batchForm.doctorId" :loading="doctorsLoading" filterable placeholder="请选择医生" style="width:100%"><el-option v-for="item in doctors" :key="item.id" :label="`${item.name} · ${item.departmentName || '未分配科室'}`" :value="item.id" :disabled="doctorOptionDisabled(item)" /></el-select><div v-if="doctorError" class="form-hint">{{ doctorError }} <el-button link @click="loadDoctors">重新加载</el-button></div></el-form-item>
        <el-form-item label="出诊日期（最多5天）" prop="dates"><el-date-picker v-model="batchForm.dates" type="dates" value-format="YYYY-MM-DD" :disabled-date="isPastDate" placeholder="选择多个日期" style="width:100%" /></el-form-item>
        <el-form-item label="时间段（最多6个）" prop="timeSlots"><el-select v-model="batchForm.timeSlots" multiple :multiple-limit="6" filterable allow-create default-first-option placeholder="选择或输入时段" style="width:100%"><el-option v-for="item in timeOptions" :key="item" :label="item" :value="item" /></el-select></el-form-item>
        <el-form-item label="每个时段总号源" prop="totalQuota"><el-input-number v-model="batchForm.totalQuota" :min="1" :max="999999" step-strictly controls-position="right" /></el-form-item>
        <el-form-item label="状态"><el-radio-group v-model="batchForm.status"><el-radio :value="1">开放</el-radio><el-radio :value="0">停诊</el-radio></el-radio-group></el-form-item>
        <p class="form-hint">本次最多生成 {{ batchCount }} 条排班，每条号源 {{ batchForm.totalQuota || 0 }} 个。</p>
      </el-form>
      <div v-else class="batch-result" role="status">
        <el-alert :type="batchResult.createdCount > 0 ? 'success' : 'warning'" :title="`创建 ${batchResult.createdCount} 条，重复跳过 ${batchResult.skippedCount} 条，过期跳过 ${batchResult.pastSkippedCount} 条`" :closable="false" show-icon />
        <ul v-if="batchResult.skippedDetails?.length"><li v-for="(detail, index) in batchResult.skippedDetails" :key="index">{{ detail }}</li></ul>
      </div>
      <template #footer><el-button :disabled="batchBusy" @click="batchVisible = false">{{ batchResult ? '关闭' : '取消' }}</el-button><el-button v-if="batchResult" type="primary" @click="openBatch">继续新增</el-button><el-button v-else type="primary" :loading="batchBusy" @click="submitBatch">生成排班</el-button></template>
    </el-dialog>
    <el-dialog
      v-model="dialogVisible"
      :title="dialogTitle"
      width="620px"
      destroy-on-close
      :close-on-click-modal="!busy"
      :close-on-press-escape="!busy"
      :show-close="!busy"
      :before-close="done => { if (!busy) done() }"
      @closed="resetForm"
    >
      <el-alert v-if="identityLocked" class="reservation-alert" type="warning" :closable="false" show-icon>
        该排班已有预约记录（含取消历史），医生、日期和时间段不可修改；仍可调整总号源与状态。
      </el-alert>
      <el-form ref="formRef" :model="form" :rules="rules" :disabled="busy" label-position="top" @submit.prevent="submit">
        <el-form-item label="医生" prop="doctorId">
          <el-select v-model="form.doctorId" :loading="doctorsLoading" filterable placeholder="请选择医生" :disabled="identityLocked" style="width: 100%">
            <el-option v-for="item in doctors" :key="item.id" :label="`${item.name} · ${item.departmentName || '未分配科室'}`" :value="item.id" :disabled="doctorOptionDisabled(item)">
              <span>{{ item.name }} · {{ item.departmentName || '未分配科室' }}</span>
              <span v-if="Number(item.status) !== 1" class="option-note">已停用</span>
            </el-option>
          </el-select>
        </el-form-item>
        <div class="form-grid two-columns">
          <el-form-item label="出诊日期" prop="scheduleDate">
            <el-date-picker v-model="form.scheduleDate" type="date" value-format="YYYY-MM-DD" placeholder="选择日期" :disabled-date="singleDateDisabled" :disabled="identityLocked" style="width: 100%" />
          </el-form-item>
          <el-form-item label="时间段" prop="timeSlot">
            <el-select v-model="form.timeSlot" filterable allow-create default-first-option placeholder="选择或输入 HH:mm/上午/下午" :disabled="identityLocked" style="width: 100%">
              <el-option v-for="item in timeOptions" :key="item" :label="item" :value="item" />
            </el-select>
          </el-form-item>
          <el-form-item label="总号源" prop="totalQuota">
            <el-input-number v-model="form.totalQuota" :min="Math.max(1, Number(form.bookedCount))" :max="999999" :step="1" step-strictly controls-position="right" style="width: 100%" />
            <div v-if="form.id" class="form-hint">当前已预约 {{ form.bookedCount }} 人，预约数由系统维护。</div>
          </el-form-item>
          <el-form-item label="状态" prop="status">
            <el-radio-group v-model="form.status">
              <el-radio :value="1">开放</el-radio>
              <el-radio :value="0">停诊</el-radio>
            </el-radio-group>
          </el-form-item>
        </div>
        <div v-if="doctorError" class="form-hint">医生候选加载失败，请返回列表页重新加载。</div>
      </el-form>
      <template #footer>
        <p class="form-hint">存在历史预约（含已取消）的排班不可删除，可使用停诊暂停预约。</p>
        <el-button :disabled="busy" @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="busy" @click="submit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.inline-alert { margin: -4px 0 16px; }
.batch-result ul { max-height: 240px; overflow: auto; padding-left: 22px; line-height: 1.9; color: #667085; }
.reservation-alert { margin-bottom: 18px; }
.form-grid.two-columns { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 18px; }
.option-note { float: right; margin-left: 18px; color: #98a2b3; font-size: 12px; }
.empty-state { display: flex; flex-direction: column; align-items: center; gap: 10px; padding: 36px 0; color: #606b7a; }
.empty-state p { margin: 0; font-weight: 600; }
@media (max-width: 640px) {
  .form-grid.two-columns { grid-template-columns: 1fr; }
}
</style>
