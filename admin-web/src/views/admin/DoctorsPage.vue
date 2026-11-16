<script setup>
import { computed, nextTick, onMounted, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { Delete, Edit, Plus, Refresh, Search, Upload } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import PageHeading from '../../components/PageHeading.vue'
import DataPagination from '../../components/DataPagination.vue'
import StatusTag from '../../components/StatusTag.vue'
import UserAvatar from '../../components/UserAvatar.vue'
import { api, confirmAction, loadAll, runAction } from '../../lib/api'
import { usePagedList } from '../../lib/usePagedList'
import { dateTime } from '../../lib/format'
import { auth } from '../../store/auth'

const { filters, rows, total, page, size, loading, error, load, search, reset } =
  usePagedList('/doctor/page', { keyword: '', departmentId: null })
const departments = ref([])
const departmentsLoading = ref(false)
const departmentError = ref('')
const formRef = ref()
const uploadRef = ref()
const dialogVisible = ref(false)
const busy = ref(false)
const avatarFile = ref(null)
let formSequence = 0
let pageActive = true
let credentialMessage = null
function closeCredentials() { credentialMessage?.close(); credentialMessage = null }
watch(() => auth.ready, ready => { if (!ready) closeCredentials() }, { flush: 'sync' })
watch(() => auth.user?.id, () => closeCredentials(), { flush: 'sync' })
onBeforeUnmount(() => { pageActive = false; closeCredentials() })
const form = reactive(defaultForm())
const selectedDepartment = computed(() => departments.value.find(item => item.id === form.departmentId))
const personnelRole = computed(() => form.id ? form.roleType : selectedDepartment.value?.type === 'PHARMACY' ? 'PHARMACIST' : 'DOCTOR')
const personnelLabel = computed(() => personnelRole.value === 'PHARMACIST' ? '配药师' : '医生')
const dialogTitle = computed(() => `${form.id ? '编辑' : '新增'}${personnelLabel.value}`)
const rules = {
  name: [
    { required: true, whitespace: true, message: '请输入医生姓名', trigger: 'blur' },
    { max: 50, message: '医生姓名不能超过 50 个字符', trigger: 'blur' },
  ],
  departmentId: [{ required: true, message: '请选择所属科室', trigger: 'change' }],
  title: [{ max: 50, message: '职称不能超过 50 个字符', trigger: 'blur' }],
  gender: [{ required: true, message: '请选择性别', trigger: 'change' }],
  intro: [{ max: 1000, message: '医生简介不能超过 1000 个字符', trigger: 'blur' }],
  status: [{ required: true, message: '请选择状态', trigger: 'change' }],
}

function defaultForm() {
  return {
    id: null, name: '', title: '', gender: 1, intro: '',
    departmentId: null, status: 1, avatar: '', username: '', roleType: 'DOCTOR',
  }
}
async function loadDepartments() {
  departmentsLoading.value = true
  departmentError.value = ''
  try { departments.value = await loadAll('/department/page') }
  catch (failure) { departmentError.value = failure.message }
  finally { departmentsLoading.value = false }
}
function clearAvatarFile() {
  avatarFile.value = null
  uploadRef.value?.clearFiles()
}
function resetForm() {
  formSequence++
  Object.assign(form, defaultForm())
  clearAvatarFile()
  nextTick(() => formRef.value?.clearValidate())
}
function openCreate() {
  if (busy.value) return
  resetForm()
  dialogVisible.value = true
}
function openEdit(row) {
  if (busy.value) return
  formSequence++
  Object.assign(form, {
    id: row.id,
    name: row.name || '',
    title: row.title || '',
    gender: Number(row.gender) === 2 ? 2 : 1,
    intro: row.intro || '',
    departmentId: row.departmentId,
    roleType: row.roleType || 'DOCTOR',
    status: Number(row.status) === 1 ? 1 : 0,
    avatar: row.avatar || '',
    username: row.loginUsername || row.username || '',
  })
  clearAvatarFile()
  dialogVisible.value = true
  nextTick(() => formRef.value?.clearValidate())
}
function chooseAvatar(uploadFile) {
  const file = uploadFile.raw
  if (!file || busy.value) return
  if (!['image/jpeg', 'image/png', 'image/gif', 'image/webp'].includes(file.type)) {
    ElMessage.warning('请选择 JPG、PNG、GIF 或 WEBP 图片')
    uploadRef.value?.clearFiles()
    avatarFile.value = null
    return
  }
  if (file.size > 2 * 1024 * 1024) {
    ElMessage.warning('头像不能超过 2MB')
    uploadRef.value?.clearFiles()
    avatarFile.value = null
    return
  }
  avatarFile.value = file
}
function genderLabel(value) { return Number(value) === 2 ? '女' : Number(value) === 1 ? '男' : '—' }
function departmentLabel(row) {
  return row.departmentName || departments.value.find(item => item.id === row.departmentId)?.name || '—'
}
function departmentDisabled(item) {
  if (Number(item.status) !== 1) return true
  // 后端仅在新增时分配账号角色，编辑时避免跨科室类型造成档案与账号角色不一致。
  return Boolean(form.id) && (item.type === 'PHARMACY') !== (form.roleType === 'PHARMACIST')
}

async function submit() {
  if (busy.value) return
  const actorId = auth.user?.id
  try { await formRef.value.validate() } catch { return }
  if (!pageActive || !auth.ready || actorId !== auth.user?.id) return
  const editing = Boolean(form.id)
  const sequence = formSequence
  const payload = {
    ...(editing ? { id: form.id } : {}),
    departmentId: form.departmentId,
    name: form.name.trim(),
    title: form.title.trim(),
    gender: form.gender,
    intro: form.intro.trim(),
    status: form.status,
    avatar: form.avatar || '',
  }
  busy.value = true
  const result = await runAction(
    () => editing ? api.put('/doctor', payload) : api.post('/doctor', payload),
    editing ? `${personnelLabel.value}信息已更新` : '',
  )
  busy.value = false
  if (!result.ok || !pageActive || !auth.ready || actorId !== auth.user?.id) return
  if (sequence !== formSequence || !dialogVisible.value) { await load(); return }
  if (editing) {
    dialogVisible.value = false
    await load()
    return
  }
  form.id = result.data?.id
  form.username = result.data?.loginUsername || ''
  const initialPassword = result.data?.initialPassword || ''
  form.roleType = selectedDepartment.value?.type === 'PHARMACY' ? 'PHARMACIST' : 'DOCTOR'
  closeCredentials()
  credentialMessage = ElMessage.success({ message: `${personnelLabel.value}已创建，登录账号：${form.username || '请刷新查看'}，初始密码：${initialPassword || '（未返回，请联系管理员重置）'}，员工可在个人信息页面自行修改密码。可继续上传照片。`, duration: 0, showClose: true })
  await load()
  nextTick(() => formRef.value?.clearValidate())
}

async function uploadAvatar() {
  if (!form.id || !avatarFile.value || busy.value) return
  if (Number(form.status) !== 1) {
    ElMessage.warning('请先启用医生并保存，再上传照片')
    return
  }
  const data = new FormData()
  const sequence = formSequence, id = form.id
  data.append('file', avatarFile.value)
  busy.value = true
  const result = await runAction(
    () => api.post(`/doctor/${id}/avatar`, data),
    '',
  )
  busy.value = false
  if (!result.ok) return
  if (sequence !== formSequence || form.id !== id || !dialogVisible.value) { await load(); return }
  form.avatar = result.data?.avatar || form.avatar
  clearAvatarFile()
  ElMessage.success('医生照片已更新')
  await load()
}

async function changeStatus(row, enabled) {
  if (busy.value) return
  const status = enabled ? 1 : 0
  const label = row.roleType === 'PHARMACIST' ? '配药师' : '医生'
  const message = status
    ? `确定启用${label}“${row.name}”吗？关联登录账号将同步启用。`
    : `确定停用${label}“${row.name}”吗？关联登录账号将同步停用，已有业务记录会保留。`
  busy.value = true
  try {
    if (!await confirmAction(message, `${status ? '启用' : '停用'}${label}`)) return
    const result = await runAction(
      () => api.put(`/doctor/${row.id}/status/${status}`),
      `${label}及账号已${status ? '启用' : '停用'}`,
    )
    if (result.ok) await load()
  } finally { busy.value = false }
}

async function remove(row) {
  if (busy.value) return
  const confirmed = await confirmAction(
    `确定删除医生“${row.name}”吗？存在排班或账号引用时不能删除，可改为停用。`,
    '删除医生',
  )
  if (!confirmed) return
  busy.value = true
  const result = await runAction(() => api.delete(`/doctor/${row.id}`), '医生已删除')
  busy.value = false
  if (result.ok) await load()
}

onMounted(() => {
  load()
  loadDepartments()
})
</script>

<template>
  <div class="page-stack">
    <PageHeading title="医生管理" description="维护医生与配药师档案，按科室类型自动创建登录账号">
      <el-button type="primary" :icon="Plus" @click="openCreate">新增医生</el-button>
    </PageHeading>

    <section class="page-panel">
      <el-form class="table-toolbar" inline @submit.prevent="search">
        <div class="toolbar-left">
          <el-form-item label="医生/账号">
            <el-input v-model="filters.keyword" class="filter-input" clearable placeholder="输入姓名或登录账号" @keyup.enter="search" />
          </el-form-item>
          <el-form-item label="所属科室">
            <el-select v-model="filters.departmentId" :loading="departmentsLoading" clearable filterable placeholder="全部科室" style="width: 180px">
              <el-option v-for="item in departments" :key="item.id" :label="item.name" :value="item.id" />
            </el-select>
          </el-form-item>
          <el-button type="primary" :icon="Search" :disabled="loading" @click="search">查询</el-button>
          <el-button :icon="Refresh" :disabled="loading" @click="reset">重置</el-button>
        </div>
      </el-form>
      <el-alert v-if="departmentError" class="inline-alert" type="warning" :closable="false" show-icon>
        <template #title>科室候选加载失败：{{ departmentError }}</template>
        <el-button link type="primary" :loading="departmentsLoading" @click="loadDepartments">重新加载</el-button>
      </el-alert>

      <el-table v-loading="loading" :data="rows" row-key="id">
        <el-table-column label="医生" min-width="190">
          <template #default="{ row }">
            <div class="cell-user">
              <UserAvatar :src="row.avatar" :name="row.name" :size="38" />
              <div><strong>{{ row.name }}</strong><div class="muted">{{ row.title || '未填写职称' }}</div></div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="所属科室" min-width="130">
          <template #default="{ row }">{{ departmentLabel(row) }}</template>
        </el-table-column>
        <el-table-column label="人员类型" width="110"><template #default="{ row }"><el-tag :type="row.roleType === 'PHARMACIST' ? 'warning' : 'primary'">{{ row.roleType === 'PHARMACIST' ? '配药师' : '医生' }}</el-tag></template></el-table-column>
        <el-table-column label="性别" width="80" align="center">
          <template #default="{ row }">{{ genderLabel(row.gender) }}</template>
        </el-table-column>
        <el-table-column label="登录账号" min-width="130">
          <template #default="{ row }">{{ row.loginUsername || row.username || '—' }}</template>
        </el-table-column>
        <el-table-column prop="intro" label="简介" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">{{ row.intro || '—' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="100" align="center">
          <template #default="{ row }"><StatusTag :status="row.status" kind="account" /></template>
        </el-table-column>
        <el-table-column label="创建时间" width="170">
          <template #default="{ row }">{{ dateTime(row.createTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="230" fixed="right">
          <template #default="{ row }">
            <div class="row-actions">
              <el-button link type="primary" :icon="Edit" :disabled="busy" @click="openEdit(row)">编辑</el-button>
              <el-button link :type="Number(row.status) === 1 ? 'warning' : 'success'" :disabled="busy" @click="changeStatus(row, Number(row.status) !== 1)">
                {{ Number(row.status) === 1 ? '停用' : '启用' }}
              </el-button>
              <el-button link type="danger" :icon="Delete" :disabled="busy" @click="remove(row)">删除</el-button>
            </div>
          </template>
        </el-table-column>
        <template #empty>
          <div class="empty-state">
            <p>{{ error ? '医生数据加载失败' : '暂无医生数据' }}</p>
            <span v-if="error" class="muted">{{ error }}</span>
            <el-button v-if="error" type="primary" plain @click="load">重新加载</el-button>
          </div>
        </template>
      </el-table>

      <DataPagination v-model:page="page" v-model:size="size" :total="total" @change="load" />
    </section>

    <el-dialog
      v-model="dialogVisible"
      :title="dialogTitle"
      width="720px"
      destroy-on-close
      :close-on-click-modal="!busy"
      :close-on-press-escape="!busy"
      :show-close="!busy"
      :before-close="done => { if (!busy) done() }"
      @closed="resetForm"
    >
      <el-alert v-if="form.id && form.username" class="account-alert" type="success" :closable="false" show-icon>
        登录账号：{{ form.username }}。员工可在个人信息页面自行修改密码，已修改的账号请使用新密码。
      </el-alert>
      <el-form ref="formRef" :model="form" :rules="rules" :disabled="busy" label-position="top" @submit.prevent="submit">
        <div class="form-grid two-columns">
          <el-form-item label="人员姓名" prop="name">
            <el-input v-model="form.name" maxlength="50" show-word-limit placeholder="请输入人员姓名" />
          </el-form-item>
          <el-form-item label="职称" prop="title">
            <el-input v-model="form.title" maxlength="50" placeholder="例如：主任医师" />
          </el-form-item>
          <el-form-item label="所属科室" prop="departmentId">
            <el-select v-model="form.departmentId" :loading="departmentsLoading" filterable placeholder="请选择科室" style="width: 100%">
              <el-option
                v-for="item in departments"
                :key="item.id"
                :label="`${item.name} · ${item.type === 'PHARMACY' ? '配药科' : '临床科室'}`"
                :value="item.id"
                :disabled="departmentDisabled(item)"
              >
                <span>{{ item.name }}</span>
                <span v-if="Number(item.status) !== 1" class="option-note">已停用</span>
              </el-option>
            </el-select>
            <div v-if="departmentError" class="form-hint">候选科室加载失败，请在上方重新加载。</div>
            <div class="form-hint">{{ form.id ? '人员类型固定，只能在相同类型科室内调动。' : `将自动创建${personnelLabel}登录账号，初始密码为123，可在个人信息页面自行修改。` }}</div>
          </el-form-item>
          <el-form-item label="性别" prop="gender">
            <el-radio-group v-model="form.gender">
              <el-radio :value="1">男</el-radio>
              <el-radio :value="2">女</el-radio>
            </el-radio-group>
          </el-form-item>
        </div>
        <el-form-item label="医生简介" prop="intro">
          <el-input v-model="form.intro" type="textarea" :rows="4" maxlength="1000" show-word-limit placeholder="填写医生擅长方向、从业经历等" />
        </el-form-item>
        <el-form-item label="状态" prop="status">
          <el-radio-group v-model="form.status">
            <el-radio :value="1">启用</el-radio>
            <el-radio :value="0">停用</el-radio>
          </el-radio-group>
          <div class="form-hint">启用或停用{{ personnelLabel }}时，绑定的登录账号会同步变更状态。</div>
        </el-form-item>
        <el-form-item label="医生照片">
          <div v-if="form.id" class="avatar-editor">
            <UserAvatar :src="form.avatar" :name="form.name" :size="64" />
            <div class="avatar-actions">
              <el-upload ref="uploadRef" :auto-upload="false" :show-file-list="false" accept="image/jpeg,image/png,image/gif,image/webp" :on-change="chooseAvatar">
                <el-button :icon="Upload" :disabled="busy || Number(form.status) !== 1">选择照片</el-button>
              </el-upload>
              <el-button type="primary" plain :disabled="!avatarFile" :loading="busy" @click="uploadAvatar">上传照片</el-button>
              <div class="form-hint">{{ avatarFile ? avatarFile.name : '支持 JPG、PNG、GIF、WEBP，最大 2MB' }}</div>
            </div>
          </div>
          <div v-else class="form-hint">保存医生后可在当前编辑界面继续上传照片。</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button :disabled="busy" @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="busy" @click="submit">{{ form.id ? '保存修改' : `新增${personnelLabel}` }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.inline-alert { margin: -4px 0 16px; }
.account-alert { margin-bottom: 18px; }
.form-grid.two-columns { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 18px; }
.option-note { float: right; margin-left: 18px; color: #98a2b3; font-size: 12px; }
.avatar-editor { display: flex; align-items: center; gap: 18px; }
.avatar-actions { display: flex; flex-wrap: wrap; align-items: center; gap: 10px; }
.empty-state { display: flex; flex-direction: column; align-items: center; gap: 10px; padding: 36px 0; color: #606b7a; }
.empty-state p { margin: 0; font-weight: 600; }
@media (max-width: 700px) {
  .form-grid.two-columns { grid-template-columns: 1fr; }
}
</style>
