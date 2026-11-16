<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Plus, Delete, Search, Refresh } from '@element-plus/icons-vue'
import { api, confirmAction, loadAll } from '../../lib/api'
import { usePagedList } from '../../lib/usePagedList'
import { dateTime, roleLabel } from '../../lib/format'
import { auth, clearSession } from '../../store/auth'
import PageHeading from '../../components/PageHeading.vue'
import DataPagination from '../../components/DataPagination.vue'
import StatusTag from '../../components/StatusTag.vue'
import UserAvatar from '../../components/UserAvatar.vue'
const { rows, total, page, size, filters, loading, error, load, search, reset } = usePagedList('/sysUser/list', { keyword: '' })
const selected = ref([]), doctors = ref([]), visible = ref(false), saving = ref(false), deleting = ref(false), formRef = ref()
const router = useRouter(), opening = ref(false)
let formSequence = 0
const form = reactive({ id: null, username: '', password: '', nickname: '', email: '', phone: '', role: 'DOCTOR', status: 1, doctorId: null })
const editing = computed(() => !!form.id), isSelf = computed(() => form.id === auth.user?.id)
const isAutomaticPharmacist = row => row?.role === 'PHARMACIST'
const editingPharmacist = computed(() => editing.value && isAutomaticPharmacist(form))
const bindingRole = computed(() => form.role === 'PHARMACIST' ? 'PHARMACIST' : 'DOCTOR')
const bindingOptions = computed(() => doctors.value.filter(person => (person.roleType || 'DOCTOR') === bindingRole.value))
const hasBindingOption = value => bindingOptions.value.some(person => String(person.id) === String(value))
const pharmacistHint = '配药师账号由配药科人员档案自动创建；可编辑账号资料、密码、状态和档案绑定，角色保持为配药师。'
const rules = {
  username: [{ required: true, min: 3, max: 50, message: '用户名为3至50个字符', trigger: 'blur' }],
  password: [{ validator: (_, value, done) => {
    if (value && !value.trim()) return done(new Error('密码不能全部为空白字符'))
    return (!editing.value || value) && (value.length < 3 || value.length > 50) ? done(new Error('密码为3至50个字符')) : done()
  }, trigger: 'blur' }],
  role: [{ required: true, message: '请选择角色', trigger: 'change' }],
  doctorId: [{ validator: (_, value, done) => {
    if (!['DOCTOR', 'PHARMACIST'].includes(form.role)) return done()
    if (!value) return done(new Error('医生或配药师账号必须绑定有效档案'))
    return hasBindingOption(value) ? done() : done(new Error('请选择与账号角色一致的有效档案'))
  }, trigger: 'change' }],
  email: [{ type: 'email', message: '请输入有效邮箱', trigger: 'blur' }],
  phone: [{ pattern: /^(1[3-9]\d{9})?$/, message: '请输入有效11位手机号', trigger: 'blur' }],
}
async function open(row) {
  if (saving.value) return
  const sequence = ++formSequence
  opening.value = true
  try {
    const candidates = await loadAll('/doctor/page')
    if (sequence !== formSequence) return
    doctors.value = candidates
    Object.assign(form, { id: null, username: '', password: '', nickname: '', email: '', phone: '', role: 'DOCTOR', status: 1, doctorId: null }, row || {}, { password: '' })
    visible.value = true
    await nextTick(); formRef.value?.clearValidate()
  } catch {} finally { if (sequence === formSequence) opening.value = false }
}
async function save() {
  if (saving.value || opening.value) return
  saving.value = true
  try {
    if (!await formRef.value.validate().catch(() => false)) return
    const self = isSelf.value, wasEditing = editing.value
    const body = { username: form.username.trim(), nickname: form.nickname, email: form.email, phone: form.phone, role: form.role, status: form.status }
    if (['DOCTOR', 'PHARMACIST'].includes(form.role)) body.doctorId = form.doctorId
    if (form.password) body.password = form.password
    if (editing.value) { body.id = form.id; await api.put('/sysUser/update', body) }
    else await api.post('/sysUser/add', body)
    if (self && body.password) {
      clearSession(); visible.value = false
      ElMessage.success('密码已修改，请使用新密码重新登录')
      await router.replace('/login'); return
    }
    if (self) auth.user = await api.get('/me')
    visible.value = false; ElMessage.success(wasEditing ? '用户已更新' : '用户已创建'); await load()
  } catch {} finally { saving.value = false }
}
async function remove(row) {
  if (row.id === auth.user?.id || deleting.value) return
  const id = row.id, name = row.nickname || row.username
  deleting.value = true
  try { if (!await confirmAction(`确定删除用户“${name}”吗？删除后该账号将无法登录。`, '删除用户')) return; await api.delete('/sysUser/delete', { params: { id } }); ElMessage.success('用户已删除'); await load() } catch {} finally { deleting.value = false }
}
async function batchDelete() {
  if (!selected.value.length || deleting.value) return
  const ids = selected.value.filter(item => item.id !== auth.user?.id).map(item => item.id)
  if (!ids.length) return
  deleting.value = true
  try { if (!await confirmAction(`确定删除选中的${ids.length}个账号吗？`)) return; await api.delete('/sysUser/batchDelete', { data: ids }); selected.value = []; ElMessage.success('所选用户已删除'); await load() } catch {} finally { deleting.value = false }
}
function closeForm(done) { if (!saving.value) { formSequence++; opening.value = false; done() } }
function cancelForm() { closeForm(() => { visible.value = false }) }
onBeforeUnmount(() => formSequence++)
onMounted(load)
</script>
<template><div><PageHeading title="用户管理" description="管理后台账号资料、角色与人员档案绑定" /><section class="page-panel"><div class="table-toolbar"><div class="toolbar-left"><el-input v-model="filters.keyword" class="filter-input" placeholder="搜索用户名 / 昵称 / 邮箱" :prefix-icon="Search" clearable @keyup.enter="search" @clear="search" /><el-button type="primary" :icon="Search" @click="search">搜索</el-button><el-button :icon="Refresh" @click="reset">重置</el-button></div><div class="toolbar-right"><el-button type="primary" :icon="Plus" @click="open()">新增用户</el-button><el-button type="danger" plain :icon="Delete" :disabled="!selected.length" :loading="deleting" @click="batchDelete">批量删除</el-button></div></div><el-alert v-if="error" type="error" :title="error" :closable="false" show-icon class="empty-error"><el-button link @click="load">重试</el-button></el-alert><el-table :data="rows" v-loading="loading" @selection-change="selected = $event"><el-table-column type="selection" width="44" :selectable="row => row.id !== auth.user?.id" /><el-table-column label="用户" min-width="210"><template #default="{ row }"><div class="cell-user"><UserAvatar :src="row.avatar" :name="row.nickname || row.username" /><div><strong>{{ row.nickname || row.username }}</strong><small>@{{ row.username }}</small></div></div></template></el-table-column><el-table-column prop="email" label="邮箱" min-width="170"><template #default="{ row }">{{ row.email || '—' }}</template></el-table-column><el-table-column label="手机号" prop="phone" min-width="135"><template #default="{ row }">{{ row.phone || '—' }}</template></el-table-column><el-table-column label="角色" width="105"><template #default="{ row }"><el-tooltip :content="isAutomaticPharmacist(row) ? pharmacistHint : ''" :disabled="!isAutomaticPharmacist(row)" placement="top"><el-tag :type="row.role === 'ADMIN' ? 'danger' : row.role === 'DOCTOR' ? 'primary' : row.role === 'PHARMACIST' ? 'warning' : 'info'" size="small" round>{{ roleLabel(row.role) }}</el-tag></el-tooltip></template></el-table-column><el-table-column label="状态" width="90"><template #default="{ row }"><StatusTag :status="row.status" /></template></el-table-column><el-table-column label="创建时间" min-width="170"><template #default="{ row }"><span class="muted text-small">{{ dateTime(row.createTime) }}</span></template></el-table-column><el-table-column label="操作" width="125" fixed="right"><template #default="{ row }"><div class="row-actions"><el-button type="primary" link @click="open(row)">编辑</el-button><el-button type="danger" link :disabled="row.id === auth.user?.id || deleting" @click="remove(row)">删除</el-button></div></template></el-table-column></el-table><p class="form-hint">配药师账号由配药科人员档案自动开户，可编辑账号资料、密码、状态和绑定档案；角色固定为配药师。删除沿用原有逻辑删除规则：删除后账号无法登录，当前登录账号不能删除。</p><DataPagination v-model:page="page" v-model:size="size" :total="total" @change="load" /></section>
<el-dialog v-model="visible" :title="editing ? '编辑用户' : '新增用户'" width="620px" :close-on-click-modal="!saving" :close-on-press-escape="!saving" :show-close="!saving" :before-close="closeForm" destroy-on-close><el-form ref="formRef" :model="form" :rules="rules" :disabled="saving || opening" label-width="88px" @submit.prevent="save"><el-form-item label="用户名" prop="username"><el-input v-model="form.username" :disabled="editing" maxlength="50" placeholder="3至50个字符" autocomplete="off" /></el-form-item><el-form-item :label="editing ? '新密码' : '密码'" prop="password"><el-input v-model="form.password" type="password" show-password :placeholder="editing ? '不修改请留空' : '至少3个字符'" maxlength="50" autocomplete="new-password" /></el-form-item><el-form-item label="昵称"><el-input v-model="form.nickname" maxlength="50" placeholder="选填" /></el-form-item><el-form-item label="邮箱" prop="email"><el-input v-model="form.email" maxlength="100" placeholder="example@mail.com" /></el-form-item><el-form-item label="手机号" prop="phone"><el-input v-model="form.phone" maxlength="11" placeholder="11位手机号" /></el-form-item><el-form-item label="角色" prop="role"><el-radio-group v-model="form.role" :disabled="isSelf || editingPharmacist"><el-radio value="ADMIN">管理员</el-radio><el-radio value="DOCTOR">医生</el-radio><el-radio v-if="editing" value="USER">用户</el-radio><el-radio v-if="editingPharmacist" value="PHARMACIST">配药师</el-radio></el-radio-group><div v-if="!editing" class="form-hint">普通用户通过微信小程序注册；配药师账号请在医生管理中新增配药科人员，由系统自动开户。</div></el-form-item><el-form-item v-if="['DOCTOR', 'PHARMACIST'].includes(form.role)" :label="form.role === 'PHARMACIST' ? '绑定药师' : '绑定医生'" prop="doctorId"><el-select v-model="form.doctorId" filterable :placeholder="form.role === 'PHARMACIST' ? '请选择配药师档案' : '请选择医生档案'" class="full-width"><el-option v-for="doctor in bindingOptions" :key="doctor.id" :label="`${doctor.name} · ${doctor.departmentName || '未分配科室'}${doctor.status === 0 ? '（已停用）' : ''}`" :value="doctor.id" /></el-select><div class="form-hint">候选按账号角色区分医生或配药师。启用账号时会同步恢复关联人员；停用最后一个可用账号时会同步停用档案。</div></el-form-item><el-form-item label="状态"><el-switch v-model="form.status" :active-value="1" :inactive-value="0" active-text="启用" inactive-text="停用" :disabled="isSelf" /></el-form-item></el-form><template #footer><el-button :disabled="saving" @click="cancelForm">取消</el-button><el-button type="primary" :loading="saving" @click="save">确定</el-button></template></el-dialog></div></template>
