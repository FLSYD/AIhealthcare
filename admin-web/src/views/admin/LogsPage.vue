<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Search, Refresh, Delete } from '@element-plus/icons-vue'
import { api, confirmAction } from '../../lib/api'
import { usePagedList } from '../../lib/usePagedList'
import { dateTime } from '../../lib/format'
import PageHeading from '../../components/PageHeading.vue'
import DataPagination from '../../components/DataPagination.vue'
const tab = ref('login'), selected = ref([]), deleting = ref(false)
const base = computed(() => tab.value === 'login' ? '/sys-login-log' : '/sys-operation-log')
const { rows, total, page, size, filters, loading, error, load, search, reset } = usePagedList(() => `${base.value}/page`, { username: '', module: '' })
async function changeTab() { selected.value = []; await reset() }
async function remove(row) {
  if (deleting.value) return
  const endpoint = base.value, id = row.id
  deleting.value = true
  try { if (!await confirmAction('确定删除这条日志吗？删除后无法恢复。', '删除日志')) return; await api.delete(`${endpoint}/${id}`); ElMessage.success('日志已删除'); await load() } catch {} finally { deleting.value = false }
}
async function removeSelected() {
  if (!selected.value.length || deleting.value) return
  const endpoint = base.value, ids = selected.value.map(row => row.id)
  deleting.value = true
  try { if (!await confirmAction(`确定删除选中的${ids.length}条日志吗？`)) return; await api.delete(`${endpoint}/batch`, { data: ids }); selected.value = []; ElMessage.success('日志已删除'); await load() } catch {} finally { deleting.value = false }
}
onMounted(load)
</script>
<template><div><PageHeading title="系统日志" description="查看登录记录与后台操作审计" /><section class="page-panel"><el-tabs v-model="tab" @tab-change="changeTab"><el-tab-pane label="登录日志" name="login" /><el-tab-pane label="操作日志" name="operation" /></el-tabs><div class="table-toolbar"><div class="toolbar-left"><el-input v-model="filters.username" placeholder="搜索用户名" :prefix-icon="Search" clearable class="filter-input" @keyup.enter="search" /><el-input v-if="tab === 'operation'" v-model="filters.module" placeholder="操作模块" clearable style="width:160px" @keyup.enter="search" /><el-button type="primary" :icon="Search" @click="search">搜索</el-button><el-button :icon="Refresh" @click="reset">重置</el-button></div><el-button type="danger" plain :icon="Delete" :disabled="!selected.length" :loading="deleting" @click="removeSelected">批量删除</el-button></div><el-alert v-if="error" :title="error" type="error" :closable="false" show-icon class="empty-error" /><el-table :data="rows" v-loading="loading" :key="tab" @selection-change="selected = $event"><el-table-column type="selection" width="44" /><el-table-column prop="username" label="用户" min-width="130" /><el-table-column prop="ip" label="IP地址" min-width="140" /><template v-if="tab === 'operation'"><el-table-column prop="module" label="操作模块" min-width="120" /><el-table-column prop="action" label="操作内容" min-width="130" /><el-table-column prop="method" label="方法" width="95" /><el-table-column prop="url" label="请求地址" min-width="190" show-overflow-tooltip /><el-table-column label="耗时" width="90"><template #default="{ row }">{{ row.costMs }}ms</template></el-table-column></template><el-table-column v-else prop="message" label="说明" min-width="200" show-overflow-tooltip /><el-table-column label="结果" width="90"><template #default="{ row }"><el-tag :type="row.status === 1 ? 'success' : 'danger'" size="small" round>{{ row.status === 1 ? '成功' : '失败' }}</el-tag></template></el-table-column><el-table-column label="记录时间" min-width="175"><template #default="{ row }">{{ dateTime(row.createTime) }}</template></el-table-column><el-table-column label="操作" width="80" fixed="right"><template #default="{ row }"><el-button type="danger" link :disabled="deleting" @click="remove(row)">删除</el-button></template></el-table-column></el-table><DataPagination v-model:page="page" v-model:size="size" :total="total" @change="load" /></section></div></template>
