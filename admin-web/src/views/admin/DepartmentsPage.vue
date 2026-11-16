<script setup>
import { computed, nextTick, onMounted, reactive, ref } from 'vue'
import { Delete, Edit, Plus, Refresh, Search } from '@element-plus/icons-vue'
import PageHeading from '../../components/PageHeading.vue'
import DataPagination from '../../components/DataPagination.vue'
import StatusTag from '../../components/StatusTag.vue'
import { api, confirmAction, runAction } from '../../lib/api'
import { usePagedList } from '../../lib/usePagedList'
import { dateTime } from '../../lib/format'

const { filters, rows, total, page, size, loading, error, load, search, reset } =
  usePagedList('/department/page', { keyword: '' })
const formRef = ref()
const dialogVisible = ref(false)
const busy = ref(false)
let formSequence = 0
const form = reactive(defaultForm())
const dialogTitle = computed(() => form.id ? '编辑科室' : '新增科室')
const rules = {
  name: [
    { required: true, whitespace: true, message: '请输入科室名称', trigger: 'blur' },
    { max: 100, message: '科室名称不能超过 100 个字符', trigger: 'blur' },
  ],
  description: [{ max: 500, message: '科室描述不能超过 500 个字符', trigger: 'blur' }],
  location: [{ max: 200, message: '科室位置不能超过 200 个字符', trigger: 'blur' }],
  sortOrder: [{ required: true, message: '请输入排序值', trigger: 'change' }],
  status: [{ required: true, message: '请选择状态', trigger: 'change' }],
  type: [{ required: true, message: '请选择科室类型', trigger: 'change' }],
}

function defaultForm() {
  return { id: null, name: '', type: 'CLINICAL', description: '', location: '', sortOrder: 0, status: 1 }
}
function resetForm() {
  formSequence++
  Object.assign(form, defaultForm())
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
    type: row.type || 'CLINICAL',
    description: row.description || '',
    location: row.location || '',
    sortOrder: row.sortOrder ?? 0,
    status: Number(row.status) === 1 ? 1 : 0,
  })
  dialogVisible.value = true
  nextTick(() => formRef.value?.clearValidate())
}
async function submit() {
  if (busy.value) return
  try { await formRef.value.validate() } catch { return }
  const editing = Boolean(form.id)
  const sequence = formSequence
  const payload = {
    ...(editing ? { id: form.id } : {}),
    name: form.name.trim(),
    type: form.type,
    description: form.description.trim(),
    location: form.location.trim(),
    sortOrder: form.sortOrder,
    status: form.status,
  }
  busy.value = true
  const result = await runAction(
    () => editing ? api.put('/department', payload) : api.post('/department', payload),
    editing ? '科室信息已更新' : '科室已新增',
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
  if (!status && !await confirmAction(`确定停用科室“${row.name}”吗？停用后将不能新增该科室医生。`)) return
  busy.value = true
  const result = await runAction(
    () => api.put(`/department/${row.id}/status/${status}`),
    status ? '科室已启用' : '科室已停用',
  )
  busy.value = false
  if (result.ok) await load()
}
async function remove(row) {
  if (busy.value) return
  const confirmed = await confirmAction(
    `确定删除科室“${row.name}”吗？已有医生引用的科室会被后端拒绝删除，可改为停用。`,
    '删除科室',
  )
  if (!confirmed) return
  busy.value = true
  const result = await runAction(() => api.delete(`/department/${row.id}`), '科室已删除')
  busy.value = false
  if (result.ok) await load()
}
onMounted(load)
</script>

<template>
  <div class="page-stack">
    <PageHeading title="科室管理" description="维护科室信息、位置与启用状态">
      <el-button type="primary" :icon="Plus" @click="openCreate">新增科室</el-button>
    </PageHeading>

    <section class="page-panel">
      <el-form class="table-toolbar search-form" inline @submit.prevent="search">
        <div class="toolbar-left">
          <el-form-item label="科室名称">
            <el-input v-model="filters.keyword" class="filter-input" clearable placeholder="输入科室名称" @keyup.enter="search" />
          </el-form-item>
          <el-button type="primary" :icon="Search" :disabled="loading" @click="search">查询</el-button>
          <el-button :icon="Refresh" :disabled="loading" @click="reset">重置</el-button>
        </div>
      </el-form>

      <el-table v-loading="loading" :data="rows" row-key="id">
        <el-table-column prop="name" label="科室名称" min-width="150">
          <template #default="{ row }"><strong>{{ row.name }}</strong></template>
        </el-table-column>
        <el-table-column prop="location" label="位置" min-width="160">
          <template #default="{ row }">{{ row.location || '—' }}</template>
        </el-table-column>
        <el-table-column label="类型" width="110"><template #default="{ row }"><el-tag :type="row.type === 'PHARMACY' ? 'warning' : 'primary'">{{ row.type === 'PHARMACY' ? '配药科' : '临床科室' }}</el-tag></template></el-table-column>
        <el-table-column prop="description" label="科室描述" min-width="240" show-overflow-tooltip>
          <template #default="{ row }">{{ row.description || '—' }}</template>
        </el-table-column>
        <el-table-column prop="sortOrder" label="排序" width="90" align="center" />
        <el-table-column label="状态" width="150" align="center">
          <template #default="{ row }">
            <div class="status-action">
              <StatusTag :status="row.status" kind="account" />
              <el-switch :model-value="Number(row.status) === 1" :loading="busy" size="small" @change="value => changeStatus(row, value)" />
            </div>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" width="170">
          <template #default="{ row }">{{ dateTime(row.createTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="190" fixed="right">
          <template #default="{ row }">
            <div class="row-actions">
              <el-button link type="primary" :icon="Edit" :disabled="busy" @click="openEdit(row)">编辑</el-button>
              <el-button link type="danger" :icon="Delete" :disabled="busy" @click="remove(row)">删除</el-button>
            </div>
          </template>
        </el-table-column>
        <template #empty>
          <div class="empty-state">
            <p>{{ error ? '科室数据加载失败' : '暂无科室数据' }}</p>
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
      width="560px"
      destroy-on-close
      :close-on-click-modal="!busy"
      :close-on-press-escape="!busy"
      :show-close="!busy"
      :before-close="done => { if (!busy) done() }"
      @closed="resetForm"
    >
      <el-form ref="formRef" :model="form" :rules="rules" :disabled="busy" label-position="top" @submit.prevent="submit">
        <el-form-item label="科室类型" prop="type"><el-radio-group v-model="form.type" :disabled="Boolean(form.id)"><el-radio value="CLINICAL">临床科室</el-radio><el-radio value="PHARMACY">配药科</el-radio></el-radio-group><div class="form-hint">配药科不在患者端展示，新增人员自动创建配药师账号。已有科室的人员角色不会自动迁移，因此创建后固定类型。</div></el-form-item>
        <el-form-item label="科室名称" prop="name">
          <el-input v-model="form.name" maxlength="100" show-word-limit placeholder="例如：心内科" />
        </el-form-item>
        <div class="form-grid">
          <el-form-item label="位置" prop="location">
            <el-input v-model="form.location" maxlength="200" placeholder="例如：门诊楼 3 层" />
          </el-form-item>
          <el-form-item label="排序" prop="sortOrder">
            <el-input-number v-model="form.sortOrder" :min="0" :step="1" step-strictly controls-position="right" />
          </el-form-item>
        </div>
        <el-form-item label="科室描述" prop="description">
          <el-input v-model="form.description" type="textarea" :rows="4" maxlength="500" show-word-limit placeholder="填写科室诊疗范围或说明" />
        </el-form-item>
        <el-form-item label="状态" prop="status">
          <el-radio-group v-model="form.status">
            <el-radio :value="1">启用</el-radio>
            <el-radio :value="0">停用</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button :disabled="busy" @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="busy" @click="submit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.search-form { margin-bottom: 16px; }
.form-grid { display: grid; grid-template-columns: minmax(0, 1fr) 160px; gap: 16px; }
.status-action { display: inline-flex; align-items: center; gap: 10px; }
.empty-state { display: flex; flex-direction: column; align-items: center; gap: 10px; padding: 36px 0; color: #606b7a; }
.empty-state p { margin: 0; font-weight: 600; }
@media (max-width: 640px) {
  .form-grid { grid-template-columns: 1fr; gap: 0; }
}
</style>
