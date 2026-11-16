<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Plus, Search, Refresh, Delete } from '@element-plus/icons-vue'
import { api, confirmAction } from '../../lib/api'
import { roleLabel } from '../../lib/format'
import { pageRegistry } from '../../router'
import { refreshMenus } from '../../store/auth'
import PageHeading from '../../components/PageHeading.vue'

const roleOptions = [
  { value: 'ADMIN', label: '管理员' },
  { value: 'DOCTOR', label: '医生' },
  { value: 'PHARMACIST', label: '配药师' },
]
const rows = ref([])
const allRows = ref([])
const loading = ref(false)
const error = ref('')
const keyword = ref('')
const selected = ref([])
const visible = ref(false)
const saving = ref(false)
const deleting = ref(false)
const formRef = ref()
let loadSequence = 0

function defaultForm() {
  return { id: null, parentId: 0, name: '', path: '', icon: '', sortOrder: 0, roles: [] }
}

const form = reactive(defaultForm())
const defaultPaths = ['/dashboard', '/doctor/overview', '/pharmacy']
const legacy = {
  '/admin/dashboard': '/dashboard',
  '/admin/departments': '/department',
  '/admin/doctors': '/doctor',
  '/admin/schedules': '/schedule',
  '/admin/medicines': '/medicine',
  '/admin/users': '/sysUser',
  '/admin/logs': '/sys-login-log',
  '/admin/menus': '/menu',
  '/admin/appointments': '/appointment',
  '/admin/doctor/dashboard': '/doctor/overview',
  '/admin/doctor/schedules': '/doctor/schedules',
  '/admin/doctor/appointments': '/doctor/appointments',
}
const canonical = path => legacy[path] || path || ''
const isDefault = row => defaultPaths.includes(canonical(row?.path))
const parseRoles = value => {
  const source = Array.isArray(value) ? value : String(value || '').split(',')
  const normalized = source.map(item => String(item).trim())
  return roleOptions.map(item => item.value).filter(role => normalized.includes(role))
}
const pageRoles = path => Array.isArray(pageRegistry[path]?.roles) ? pageRegistry[path].roles : []
const rolesForRow = row => parseRoles(row?.roles)
const roleTagType = role => role === 'ADMIN' ? 'danger' : role === 'DOCTOR' ? 'primary' : 'warning'
const choices = computed(() => Object.entries(pageRegistry)
  .filter(([path]) => !allRows.value.some(row => canonical(row.path) === path && row.id !== form.id))
  .map(([path, value]) => ({ path, ...value })))
const selectedPathRoles = computed(() => pageRoles(form.path))
const rules = {
  name: [{ required: true, whitespace: true, message: '请输入菜单名称', trigger: 'blur' }],
  path: [{ required: true, message: '请选择页面', trigger: 'change' }],
  roles: [{ type: 'array', required: true, min: 1, message: '请至少选择一个角色', trigger: 'change' }],
}

async function load() {
  const sequence = ++loadSequence
  loading.value = true
  error.value = ''
  try {
    const data = await api.get('/menus/manage')
    if (sequence !== loadSequence) return
    allRows.value = Array.isArray(data) ? data : []
    const search = keyword.value.trim().toLowerCase()
    rows.value = allRows.value.filter(row => !search || (row.name || '').toLowerCase().includes(search))
    selected.value = []
  } catch (failure) {
    if (sequence === loadSequence) error.value = failure.message
  } finally {
    if (sequence === loadSequence) loading.value = false
  }
}

async function open(row) {
  if (saving.value) return
  const path = canonical(row?.path)
  const existingRoles = row ? rolesForRow(row) : []
  Object.assign(form, defaultForm(), row || {}, {
    path,
    roles: defaultPaths.includes(path) || !existingRoles.length ? [...pageRoles(path)] : existingRoles,
  })
  visible.value = true
  await nextTick()
  formRef.value?.clearValidate()
}

function selectPath(path) {
  const definition = pageRegistry[path]
  if (!definition) return
  Object.assign(form, {
    roles: [...definition.roles],
    icon: definition.icon,
    name: form.name || definition.title,
  })
  formRef.value?.clearValidate('roles')
}

async function save() {
  if (saving.value) return
  saving.value = true
  try {
    if (!await formRef.value.validate().catch(() => false)) return
    const body = {
      parentId: 0,
      name: form.name.trim(),
      path: form.path,
      icon: form.icon,
      sortOrder: form.sortOrder,
      roles: form.roles.join(','),
    }
    if (form.id) await api.put(`/menus/${form.id}`, body)
    else await api.post('/menus', body)
    visible.value = false
    ElMessage.success('菜单已保存')
    await Promise.all([load(), refreshMenus()])
  } catch {
  } finally {
    saving.value = false
  }
}

async function remove(row) {
  if (deleting.value) return
  const id = row.id
  deleting.value = true
  try {
    if (!await confirmAction(`确定删除菜单“${row.name}”吗？对应页面和角色权限不会被删除。`)) return
    await api.delete(`/menus/${id}`)
    ElMessage.success('菜单已删除')
    await Promise.all([load(), refreshMenus()])
  } catch {
  } finally {
    deleting.value = false
  }
}

async function removeSelected() {
  if (!selected.value.length || deleting.value) return
  const ids = selected.value.map(row => row.id)
  deleting.value = true
  try {
    if (!await confirmAction(`确定删除选中的${ids.length}个菜单吗？`)) return
    await api.delete('/menus/batch', { data: ids })
    ElMessage.success('所选菜单已删除')
    await Promise.all([load(), refreshMenus()])
  } catch {
  } finally {
    deleting.value = false
  }
}

onMounted(load)
onBeforeUnmount(() => loadSequence++)
</script>

<template>
  <div>
    <PageHeading title="菜单管理" description="设置工作台菜单的名称、顺序与可访问角色" />
    <section class="page-panel">
      <div class="table-toolbar">
        <div class="toolbar-left">
          <el-input
            v-model="keyword"
            placeholder="搜索菜单名称"
            :prefix-icon="Search"
            class="filter-input"
            clearable
            @keyup.enter="load"
            @clear="load"
          />
          <el-button type="primary" :icon="Search" @click="load">搜索</el-button>
          <el-button :icon="Refresh" @click="keyword = ''; load()">重置</el-button>
        </div>
        <div class="toolbar-right">
          <el-button type="primary" :icon="Plus" @click="open()">新增菜单</el-button>
          <el-button type="danger" plain :icon="Delete" :disabled="!selected.length" :loading="deleting" @click="removeSelected">批量删除</el-button>
        </div>
      </div>

      <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon class="empty-error" />
      <el-table :data="rows" v-loading="loading" @selection-change="selected = $event">
        <el-table-column type="selection" width="44" :selectable="row => !isDefault(row)" />
        <el-table-column prop="name" label="菜单名称" min-width="150" />
        <el-table-column label="页面路径" min-width="230">
          <template #default="{ row }"><span class="muted">{{ canonical(row.path) }}</span></template>
        </el-table-column>
        <el-table-column label="所属角色" width="190">
          <template #default="{ row }">
            <div class="role-tags">
              <el-tag v-for="role in rolesForRow(row)" :key="role" :type="roleTagType(role)" size="small" round>
                {{ roleLabel(role) }}
              </el-tag>
              <span v-if="!rolesForRow(row).length" class="muted">未配置</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="icon" label="图标" min-width="140" />
        <el-table-column prop="sortOrder" label="排序" width="100" />
        <el-table-column label="操作" width="130" fixed="right">
          <template #default="{ row }">
            <div class="row-actions">
              <el-button type="primary" link @click="open(row)">编辑</el-button>
              <el-button type="danger" link :disabled="isDefault(row) || deleting" @click="remove(row)">删除</el-button>
            </div>
          </template>
        </el-table-column>
      </el-table>
      <p class="form-hint" style="margin-top: 18px">
        管理员与医生的默认首页由后端保护；所有菜单角色都必须落在页面路径允许的角色范围内。菜单配置不改变接口权限。
      </p>
    </section>

    <el-dialog
      v-model="visible"
      :title="form.id ? '编辑菜单' : '新增菜单'"
      width="560px"
      :close-on-click-modal="!saving"
      :close-on-press-escape="!saving"
      :show-close="!saving"
      :before-close="done => { if (!saving) done() }"
      destroy-on-close
    >
      <el-form ref="formRef" :model="form" :rules="rules" :disabled="saving" label-width="88px">
        <el-form-item label="菜单名称" prop="name">
          <el-input v-model="form.name" maxlength="50" />
        </el-form-item>
        <el-form-item label="页面" prop="path">
          <el-select
            v-model="form.path"
            filterable
            class="full-width"
            placeholder="选择已有页面"
            :disabled="defaultPaths.includes(form.path) && !!form.id"
            @change="selectPath"
          >
            <el-option
              v-for="item in choices"
              :key="item.path"
              :label="`${item.title} · ${item.roles.map(roleLabel).join('、')}`"
              :value="item.path"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="所属角色" prop="roles">
          <el-checkbox-group v-model="form.roles">
            <el-checkbox
              v-for="option in roleOptions.filter(item => selectedPathRoles.includes(item.value))"
              :key="option.value"
              :value="option.value"
              :disabled="selectedPathRoles.length === 1"
            >
              {{ option.label }}
            </el-checkbox>
          </el-checkbox-group>
        </el-form-item>
        <el-form-item label="图标名称">
          <el-input v-model="form.icon" maxlength="50" />
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="form.sortOrder" :min="0" :max="1000000" :precision="0" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button :disabled="saving" @click="visible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.role-tags { display: flex; flex-wrap: wrap; gap: 6px; }
</style>
