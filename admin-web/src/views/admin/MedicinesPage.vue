<script setup>
import { computed, nextTick, onMounted, reactive, ref } from 'vue'
import { Delete, Edit, Plus, Refresh, Search } from '@element-plus/icons-vue'
import PageHeading from '../../components/PageHeading.vue'
import DataPagination from '../../components/DataPagination.vue'
import StatusTag from '../../components/StatusTag.vue'
import { api, confirmAction, runAction } from '../../lib/api'
import { usePagedList } from '../../lib/usePagedList'
import { dateTime, money } from '../../lib/format'
import { auth } from '../../store/auth'

const { filters, rows, total, page, size, loading, error, load, search, reset } =
  usePagedList('/medicine/page', { keyword: '', status: null })
const formRef = ref()
const dialogVisible = ref(false)
const busy = ref(false)
let formSequence = 0
const stockSnapshot = ref(0)
const form = reactive(defaultForm())
const canManage = computed(() => Array.isArray(auth.permissions?.permissions)
  && auth.permissions.permissions.includes('medicine:manage'))
const pageTitle = computed(() => canManage.value ? '药品管理' : '药品查询')
const pageDescription = computed(() => canManage.value
  ? '维护药品规格、价格、库存与处方可用状态'
  : '只读查看药品规格、价格、库存与可用状态')
const dialogTitle = computed(() => form.id ? '编辑药品' : '新增药品')
const rules = {
  name: [
    { required: true, whitespace: true, message: '请输入药品名称', trigger: 'blur' },
    { max: 100, message: '药品名称不能超过 100 个字符', trigger: 'blur' },
  ],
  spec: [
    { max: 100, message: '药品规格不能超过 100 个字符', trigger: 'blur' },
  ],
  unit: [
    { required: true, whitespace: true, message: '请输入药品单位', trigger: 'blur' },
    { max: 20, message: '药品单位不能超过 20 个字符', trigger: 'blur' },
  ],
  price: [{ validator: validatePrice, trigger: 'change' }],
  stock: [{ validator: validateStock, trigger: 'change' }],
  usageDesc: [{ max: 200, message: '用法说明不能超过 200 个字符', trigger: 'blur' }],
  status: [{ required: true, message: '请选择状态', trigger: 'change' }],
}

function defaultForm() {
  return {
    id: null, name: '', spec: '', unit: '盒', price: 0, stock: 0,
    usageDesc: '', status: 1,
  }
}
function validatePrice(rule, value, callback) {
  if (value == null || Number.isNaN(Number(value))) return callback(new Error('请输入单价'))
  if (Number(value) < 0) return callback(new Error('单价不能为负数'))
  if (Number(value) > 99999999.99) return callback(new Error('单价不能超过 99999999.99'))
  if ((String(value).split('.')[1] || '').length > 2) return callback(new Error('单价最多保留 2 位小数'))
  callback()
}
function validateStock(rule, value, callback) {
  if (value == null) return callback(new Error('请输入库存'))
  if (!Number.isInteger(value) || value < 0) return callback(new Error('库存必须为不小于 0 的整数'))
  if (value > 2147483647) return callback(new Error('库存超出允许范围'))
  callback()
}
function resetForm() {
  formSequence++
  Object.assign(form, defaultForm())
  stockSnapshot.value = 0
  nextTick(() => formRef.value?.clearValidate())
}
function openCreate() {
  if (!canManage.value || busy.value) return
  resetForm()
  dialogVisible.value = true
}
function openEdit(row) {
  if (!canManage.value || busy.value) return
  formSequence++
  Object.assign(form, {
    id: row.id,
    name: row.name || '',
    spec: row.spec || '',
    unit: row.unit || '盒',
    price: Number(row.price || 0),
    stock: Number(row.stock || 0),
    usageDesc: row.usageDesc || '',
    status: Number(row.status) === 1 ? 1 : 0,
  })
  stockSnapshot.value = Number(row.stock || 0)
  dialogVisible.value = true
  nextTick(() => formRef.value?.clearValidate())
}
async function submit() {
  if (!canManage.value || busy.value) return
  try { await formRef.value.validate() } catch { return }
  const editing = Boolean(form.id)
  const sequence = formSequence
  const payload = {
    ...(editing ? { id: form.id, expectedStock: stockSnapshot.value } : {}),
    name: form.name.trim(),
    spec: form.spec.trim(),
    unit: form.unit.trim(),
    price: Number(form.price),
    stock: form.stock,
    usageDesc: form.usageDesc.trim(),
    status: form.status,
  }
  busy.value = true
  const result = await runAction(
    () => editing ? api.put('/medicine', payload) : api.post('/medicine', payload),
    editing ? '药品信息已更新' : '药品已新增',
  )
  busy.value = false
  if (!result.ok) return
  if (sequence !== formSequence || !dialogVisible.value) { await load(); return }
  dialogVisible.value = false
  await load()
}
async function changeStatus(row, enabled) {
  if (!canManage.value || busy.value) return
  const status = enabled ? 1 : 0
  if (!status && !await confirmAction(`确定停用药品“${row.name}”吗？停用后将不能用于新处方。`, '停用药品')) return
  busy.value = true
  const result = await runAction(
    () => api.put(`/medicine/${row.id}/status/${status}`),
    status ? '药品已启用' : '药品已停用',
  )
  busy.value = false
  if (result.ok) await load()
}
async function remove(row) {
  if (!canManage.value || busy.value) return
  const confirmed = await confirmAction(
    `确定删除药品“${row.name}”吗？已被处方引用的药品会被后端拒绝删除，可改为停用。`,
    '删除药品',
  )
  if (!confirmed) return
  busy.value = true
  const result = await runAction(() => api.delete(`/medicine/${row.id}`), '药品已删除')
  busy.value = false
  if (result.ok) await load()
}
onMounted(load)
</script>

<template>
  <div class="page-stack">
    <PageHeading :title="pageTitle" :description="pageDescription">
      <el-button v-if="canManage" type="primary" :icon="Plus" @click="openCreate">新增药品</el-button>
    </PageHeading>

    <section class="page-panel">
      <el-form class="table-toolbar" inline @submit.prevent="search">
        <div class="toolbar-left">
          <el-form-item label="药品名称">
            <el-input v-model="filters.keyword" class="filter-input" clearable placeholder="输入药品名称" @keyup.enter="search" />
          </el-form-item>
          <el-form-item label="状态">
            <el-select v-model="filters.status" clearable placeholder="全部状态" style="width: 130px">
              <el-option label="启用" :value="1" />
              <el-option label="停用" :value="0" />
            </el-select>
          </el-form-item>
          <el-button type="primary" :icon="Search" :disabled="loading" @click="search">查询</el-button>
          <el-button :icon="Refresh" :disabled="loading" @click="reset">重置</el-button>
        </div>
      </el-form>

      <el-table v-loading="loading" :data="rows" row-key="id">
        <el-table-column prop="name" label="药品名称" min-width="170">
          <template #default="{ row }"><strong>{{ row.name }}</strong></template>
        </el-table-column>
        <el-table-column prop="spec" label="规格" min-width="150">
          <template #default="{ row }">{{ row.spec || '—' }}</template>
        </el-table-column>
        <el-table-column prop="unit" label="单位" width="80" align="center" />
        <el-table-column label="单价" width="120" align="right">
          <template #default="{ row }">{{ money(row.price) }}</template>
        </el-table-column>
        <el-table-column label="库存" width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="Number(row.stock) <= 10 ? 'warning' : 'info'" effect="plain">{{ row.stock }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="usageDesc" label="用法说明" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">{{ row.usageDesc || '—' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="100" align="center">
          <template #default="{ row }"><StatusTag :status="row.status" kind="account" /></template>
        </el-table-column>
        <el-table-column label="更新时间" width="170">
          <template #default="{ row }">{{ dateTime(row.updateTime) }}</template>
        </el-table-column>
        <el-table-column v-if="canManage" label="操作" width="230" fixed="right">
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
            <p>{{ error ? '药品数据加载失败' : '暂无药品数据' }}</p>
            <span v-if="error" class="muted">{{ error }}</span>
            <el-button v-if="error" type="primary" plain @click="load">重新加载</el-button>
          </div>
        </template>
      </el-table>

      <DataPagination v-model:page="page" v-model:size="size" :total="total" @change="load" />
    </section>

    <el-dialog
      v-if="canManage"
      v-model="dialogVisible"
      :title="dialogTitle"
      width="680px"
      destroy-on-close
      :close-on-click-modal="!busy"
      :close-on-press-escape="!busy"
      :show-close="!busy"
      :before-close="done => { if (!busy) done() }"
      @closed="resetForm"
    >
      <el-form ref="formRef" :model="form" :rules="rules" :disabled="busy" label-position="top" @submit.prevent="submit">
        <div class="form-grid two-columns">
          <el-form-item label="药品名称" prop="name">
            <el-input v-model="form.name" maxlength="100" show-word-limit placeholder="例如：阿莫西林胶囊" />
          </el-form-item>
          <el-form-item label="规格" prop="spec">
            <el-input v-model="form.spec" maxlength="100" placeholder="例如：0.25g*24片" />
          </el-form-item>
          <el-form-item label="单位" prop="unit">
            <el-input v-model="form.unit" maxlength="20" placeholder="例如：盒" />
          </el-form-item>
          <el-form-item label="单价（元）" prop="price">
            <el-input-number v-model="form.price" :min="0" :max="99999999.99" :precision="2" :step="0.01" controls-position="right" style="width: 100%" />
          </el-form-item>
          <el-form-item label="库存" prop="stock">
            <el-input-number v-model="form.stock" :min="0" :max="2147483647" :step="1" step-strictly controls-position="right" style="width: 100%" />
            <div v-if="form.id" class="form-hint">打开时库存：{{ stockSnapshot }}。若库存期间发生变化，请刷新后重新修改。</div>
          </el-form-item>
          <el-form-item label="状态" prop="status">
            <el-radio-group v-model="form.status">
              <el-radio :value="1">启用</el-radio>
              <el-radio :value="0">停用</el-radio>
            </el-radio-group>
          </el-form-item>
        </div>
        <el-form-item label="用法说明" prop="usageDesc">
          <el-input v-model="form.usageDesc" type="textarea" :rows="4" maxlength="200" show-word-limit placeholder="填写剂量、频次或注意事项" />
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
.form-grid.two-columns { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 18px; }
.empty-state { display: flex; flex-direction: column; align-items: center; gap: 10px; padding: 36px 0; color: #606b7a; }
.empty-state p { margin: 0; font-weight: 600; }
@media (max-width: 640px) {
  .form-grid.two-columns { grid-template-columns: 1fr; }
}
</style>
