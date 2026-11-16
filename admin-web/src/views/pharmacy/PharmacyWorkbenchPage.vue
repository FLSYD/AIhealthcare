<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { CircleCheck, FirstAidKit, Refresh, Search, View } from '@element-plus/icons-vue'
import PageHeading from '../../components/PageHeading.vue'
import DataPagination from '../../components/DataPagination.vue'
import StatusTag from '../../components/StatusTag.vue'
import { api, confirmAction } from '../../lib/api'
import { dateTime, money } from '../../lib/format'
import { usePagedList } from '../../lib/usePagedList'
import { useStatusRefresh } from '../../lib/useStatusRefresh'
import { parsePharmacyQuery } from '../../lib/notificationRoute'
import { auth } from '../../store/auth'

const route = useRoute()
const router = useRouter()
const paged = usePagedList('/pharmacy/prescriptions', {
  status: 'paid',
  keyword: '',
})
const { filters, rows, total, page, size, loading, error, load, search, reset } = paged

const drawerVisible = ref(false)
const selectedId = ref(null)
const detail = ref(null)
const detailLoading = ref(false)
const detailError = ref('')
const actionError = ref('')
const dispensing = ref(false)
const queryError = ref('')
let detailSequence = 0
let querySequence = 0

const detailItems = computed(() => Array.isArray(detail.value?.items) ? detail.value.items : [])
const canDispense = computed(() => {
  const permissions = Array.isArray(auth.permissions?.permissions) ? auth.permissions.permissions : []
  return permissions.includes('prescription:dispense') && detail.value?.status === 'paid'
})

function openDetail(row) {
  if (dispensing.value) return
  const id = String(row?.id || '')
  if (!/^[1-9]\d*$/.test(id)) return
  selectedId.value = id
  detail.value = null
  detailError.value = ''
  actionError.value = ''
  drawerVisible.value = true
  loadDetail()
}

async function loadDetail({ background = false } = {}) {
  const id = selectedId.value
  if (!drawerVisible.value || !id) return
  const current = ++detailSequence
  if (!background) {
    detailLoading.value = true
    detailError.value = ''
  }
  try {
    const data = await api.get(`/pharmacy/prescriptions/${encodeURIComponent(id)}`, { quiet: background })
    if (current !== detailSequence || selectedId.value !== id || !drawerVisible.value) return
    detail.value = data || null
    detailError.value = ''
  } catch (failure) {
    if (current === detailSequence && selectedId.value === id && drawerVisible.value) {
      detailError.value = background
        ? `状态同步失败，当前显示上次结果：${failure.message}`
        : failure.message
    }
  } finally {
    if (current === detailSequence) detailLoading.value = false
  }
}

function closeDetail() {
  drawerVisible.value = false
}

// 抽屉关闭后清掉 query 中的 id/status，使同一处方的通知可再次触发打开
function clearDetailQuery() {
  if (route.query.id === undefined && route.query.status === undefined) return
  const query = { ...route.query }
  delete query.id
  delete query.status
  router.replace({ path: route.path, query })
}

// 按路由 query 同步队列与详情：先切换并重载 status 队列，再打开指定处方。
// querySequence 用来丢弃被更新的路由变化取代的旧同步。
async function syncFromRoute({ initial = false } = {}) {
  const current = ++querySequence
  if (dispensing.value) return
  const { id, status, invalidId } = parsePharmacyQuery(route.query)
  queryError.value = invalidId ? '通知中的处方编号无效，无法打开对应处方' : ''
  const targetStatus = status || (id ? 'paid' : null)
  if (id && (id !== selectedId.value || (targetStatus && targetStatus !== filters.status))) {
    detailSequence++
    selectedId.value = null
    detail.value = null
    detailError.value = ''
    detailLoading.value = false
  }
  if (targetStatus && targetStatus !== filters.status) {
    filters.status = targetStatus
    await search()
  } else if (initial) {
    await load()
  }
  if (current !== querySequence) return
  if (!id) return
  if (dispensing.value) return
  if (drawerVisible.value && selectedId.value === id) return
  openDetail({ id })
}

function resetDetail() {
  detailSequence++
  selectedId.value = null
  detail.value = null
  detailLoading.value = false
  detailError.value = ''
  actionError.value = ''
  dispensing.value = false
}

async function changeStatus() {
  if (drawerVisible.value) closeDetail()
  await search()
}

async function resetFilters() {
  await reset()
}

async function refreshCurrent() {
  if (drawerVisible.value && selectedId.value) await loadDetail()
  else await load()
}

async function dispense() {
  if (dispensing.value || detailLoading.value || detailError.value || !canDispense.value || !selectedId.value) return
  const id = selectedId.value
  let actionFailed = false
  dispensing.value = true
  actionError.value = ''
  try {
    if (!await confirmAction('确认已按处方完成全部药品发放吗？操作后将扣减库存并完成预约。', '确认发药')) return
    if (!drawerVisible.value || id !== selectedId.value || !canDispense.value) return
    const latest = await api.get(`/pharmacy/prescriptions/${encodeURIComponent(id)}`)
    if (!drawerVisible.value || id !== selectedId.value) return
    detail.value = latest
    if (latest.status !== 'paid') { ElMessage.warning('处方状态已变化，请按最新状态操作'); return }
    await api.post(`/pharmacy/prescriptions/${encodeURIComponent(id)}/dispense`)
    ElMessage.success('发药成功')
  } catch (failure) {
    actionFailed = true
    actionError.value = `发药结果尚未确认，请核对最新处方状态：${failure.message}`
  } finally {
    if (id === selectedId.value && drawerVisible.value) {
      await Promise.all([
        loadDetail({ background: true }),
        load({ background: true }),
      ])
      if (actionFailed && !detailError.value && detail.value?.status === 'dispensed') {
        actionError.value = ''
        ElMessage.success('最新状态为已发药，无需重复操作')
      }
    }
    dispensing.value = false
  }
}

useStatusRefresh(async () => {
  if (drawerVisible.value && selectedId.value) await loadDetail({ background: true })
  else await load({ background: true })
}, {
  enabled: () => !loading.value && !detailLoading.value && !dispensing.value,
})

onBeforeUnmount(() => {
  detailSequence++
  querySequence++
})
watch(() => [route.query.id, route.query.status], () => syncFromRoute())
watch(drawerVisible, visible => { if (!visible) clearDetailQuery() })
watch(dispensing, value => { if (!value && parsePharmacyQuery(route.query).id) syncFromRoute() })
onMounted(() => syncFromRoute({ initial: true }))
</script>

<template>
  <section class="page-stack pharmacy-page">
    <PageHeading title="配药工作台" description="按已付款处方核对药品并发药，完成后预约将同步结束">
      <el-button :icon="Refresh" :disabled="dispensing" :loading="loading || detailLoading" @click="refreshCurrent">刷新状态</el-button>
    </PageHeading>

    <el-alert v-if="queryError" :title="queryError" type="warning" show-icon :closable="false" />

    <div class="pharmacy-summary">
      <article class="summary-card">
        <div class="summary-icon"><el-icon><FirstAidKit /></el-icon></div>
        <div><span>当前队列</span><strong>{{ filters.status === 'paid' ? '待发药' : '已发药' }}</strong></div>
      </article>
      <article class="summary-card">
        <div class="summary-icon is-success"><el-icon><CircleCheck /></el-icon></div>
        <div><span>筛选结果</span><strong>{{ total }} 张处方</strong></div>
      </article>
    </div>

    <section class="page-panel">
      <div class="table-toolbar pharmacy-toolbar">
        <div class="toolbar-left">
          <el-radio-group v-model="filters.status" :disabled="dispensing" @change="changeStatus">
            <el-radio-button value="paid">待发药</el-radio-button>
            <el-radio-button value="dispensed">已发药</el-radio-button>
          </el-radio-group>
        </div>
        <div class="toolbar-right">
          <el-input
            v-model="filters.keyword"
            clearable
            class="pharmacy-search"
            placeholder="处方号 / 患者姓名 / 医生姓名"
            :prefix-icon="Search"
            @keyup.enter="search"
            @clear="search"
          />
          <el-button type="primary" :icon="Search" :loading="loading" @click="search">查询</el-button>
          <el-button @click="resetFilters">重置</el-button>
        </div>
      </div>

      <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" class="empty-error">
        <template #default><el-button link type="primary" @click="load">重新加载</el-button></template>
      </el-alert>

      <div class="table-scroll-x">
        <el-table v-loading="loading" :data="rows" row-key="id" stripe>
          <el-table-column label="处方号" min-width="105">
            <template #default="{ row }"><strong>#{{ row.id }}</strong></template>
          </el-table-column>
          <el-table-column prop="patientName" label="患者" min-width="120" show-overflow-tooltip>
            <template #default="{ row }">{{ row.patientName || '—' }}</template>
          </el-table-column>
          <el-table-column prop="patientPhone" label="联系电话" min-width="135">
            <template #default="{ row }">
              <a v-if="row.patientPhone" :href="`tel:${row.patientPhone}`">{{ row.patientPhone }}</a>
              <span v-else class="muted">—</span>
            </template>
          </el-table-column>
          <el-table-column prop="doctorName" label="开方医生" min-width="120" show-overflow-tooltip>
            <template #default="{ row }">{{ row.doctorName || '—' }}</template>
          </el-table-column>
          <el-table-column prop="departmentName" label="科室" min-width="130" show-overflow-tooltip>
            <template #default="{ row }">{{ row.departmentName || '—' }}</template>
          </el-table-column>
          <el-table-column label="处方金额" width="120" align="right">
            <template #default="{ row }">{{ money(row.totalAmount) }}</template>
          </el-table-column>
          <el-table-column label="状态" width="105" align="center">
            <template #default="{ row }"><StatusTag :status="row.status" kind="prescription" /></template>
          </el-table-column>
          <el-table-column label="缴费时间" min-width="165">
            <template #default="{ row }">{{ dateTime(row.payTime) }}</template>
          </el-table-column>
          <el-table-column label="更新时间" min-width="165">
            <template #default="{ row }">{{ dateTime(row.updateTime) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="105" fixed="right" align="center">
            <template #default="{ row }">
              <el-button link type="primary" :icon="View" @click="openDetail(row)">查看详情</el-button>
            </template>
          </el-table-column>
          <template #empty>
            <el-empty
              v-if="!loading && !error"
              :description="filters.status === 'paid' ? '暂无待发药处方' : '暂无已发药处方'"
            />
          </template>
        </el-table>
      </div>

      <DataPagination v-model:page="page" v-model:size="size" :total="total" @change="load" />
    </section>

    <el-drawer
      v-model="drawerVisible"
      size="min(920px, 96vw)"
      :close-on-click-modal="!dispensing"
      :close-on-press-escape="!dispensing"
      :show-close="!dispensing"
      append-to-body
      @closed="resetDetail"
    >
      <template #header>
        <div class="drawer-heading">
          <div>
            <h2>处方详情</h2>
            <p v-if="detail">处方 #{{ detail.id }} · {{ detail.patientName || '未设置患者' }}</p>
          </div>
          <StatusTag v-if="detail" :status="detail.status" kind="prescription" />
        </div>
      </template>

      <div v-loading="detailLoading" class="detail-body">
        <el-alert v-if="detailError" :title="detailError" type="error" show-icon :closable="false">
          <template #default><el-button link type="primary" @click="loadDetail()">重新加载</el-button></template>
        </el-alert>
        <el-alert v-if="actionError" :title="actionError" type="error" show-icon :closable="false" />

        <template v-if="detail">
          <section class="detail-toolbar">
            <div>
              <strong>{{ ({ paid: '请核对患者与药品信息后发药', dispensed: '该处方已完成发药', unpaid: '患者尚未缴费，不能发药', cancelled: '该处方已取消，不能发药' })[detail.status] || '处方状态未知，请联系管理员核对' }}</strong>
              <p>处方状态以服务端最新读取结果为准。</p>
            </div>
            <el-button :icon="Refresh" :loading="detailLoading || dispensing" :disabled="dispensing" @click="loadDetail">刷新状态</el-button>
          </section>

          <el-descriptions :column="2" border class="detail-descriptions">
            <el-descriptions-item label="患者">{{ detail.patientName || '—' }}</el-descriptions-item>
            <el-descriptions-item label="联系电话">{{ detail.patientPhone || '—' }}</el-descriptions-item>
            <el-descriptions-item label="开方医生">{{ detail.doctorName || '—' }}</el-descriptions-item>
            <el-descriptions-item label="科室">{{ detail.departmentName || '—' }}</el-descriptions-item>
            <el-descriptions-item label="处方金额">{{ money(detail.totalAmount) }}</el-descriptions-item>
            <el-descriptions-item label="支付方式">{{ detail.payMethod || '—' }}</el-descriptions-item>
            <el-descriptions-item label="缴费时间">{{ dateTime(detail.payTime) }}</el-descriptions-item>
            <el-descriptions-item label="开方时间">{{ dateTime(detail.createTime) }}</el-descriptions-item>
          </el-descriptions>

          <section class="detail-section">
            <div class="section-title">
              <div>
                <h3>药品清单</h3>
                <p>请按处方逐项核对规格、数量与用法。</p>
              </div>
              <el-tag type="info" effect="plain">共 {{ detailItems.length }} 项</el-tag>
            </div>
            <div class="table-scroll-x">
              <el-table :data="detailItems" row-key="medicineId" border>
                <el-table-column prop="medicineName" label="药品" min-width="150" show-overflow-tooltip />
                <el-table-column label="规格" min-width="120">
                  <template #default="{ row }">{{ row.spec || '—' }}</template>
                </el-table-column>
                <el-table-column prop="quantity" label="数量" width="80" align="center" />
                <el-table-column label="单价" width="110" align="right">
                  <template #default="{ row }">{{ money(row.unitPrice) }}</template>
                </el-table-column>
                <el-table-column label="小计" width="110" align="right">
                  <template #default="{ row }">{{ money(row.amount) }}</template>
                </el-table-column>
                <el-table-column label="用法用量" min-width="160" show-overflow-tooltip>
                  <template #default="{ row }">{{ row.dosage || '—' }}</template>
                </el-table-column>
                <el-table-column label="频次" min-width="105">
                  <template #default="{ row }">{{ row.frequency || '—' }}</template>
                </el-table-column>
                <el-table-column label="天数" width="80" align="center">
                  <template #default="{ row }">{{ row.days ?? '—' }}</template>
                </el-table-column>
              </el-table>
            </div>
          </section>

          <div class="detail-actions">
            <el-button :disabled="dispensing" @click="closeDetail">关闭</el-button>
            <el-button
              v-if="canDispense"
              type="success"
              :icon="CircleCheck"
              :loading="dispensing"
              :disabled="dispensing || detailLoading || Boolean(detailError)"
              @click="dispense"
            >
              确认发药
            </el-button>
          </div>
        </template>

        <el-empty v-else-if="!detailLoading && !detailError" description="未读取到处方详情" />
      </div>
    </el-drawer>
  </section>
</template>

<style scoped>
.pharmacy-page { gap: 18px; }
.pharmacy-summary { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; }
.summary-card { display: flex; align-items: center; gap: 15px; padding: 19px 22px; background: #fff; border: 1px solid #e4ebf4; border-radius: 12px; box-shadow: 0 2px 5px #26395b05; }
.summary-icon { width: 46px; height: 46px; display: flex; align-items: center; justify-content: center; flex: 0 0 46px; border-radius: 14px; color: #3378ed; background: #edf3ff; font-size: 22px; }
.summary-icon.is-success { color: #2b9e70; background: #eaf8f2; }
.summary-card span { display: block; color: #8a98ad; font-size: 12px; }
.summary-card strong { display: block; margin-top: 6px; color: #21334b; font-size: 19px; }
.pharmacy-toolbar { align-items: center; }
.pharmacy-search { width: 300px !important; }
.table-scroll-x { overflow-x: auto; }
.table-scroll-x :deep(.el-table) { min-width: 1160px; }
a { color: #337ecc; text-decoration: none; }
a:hover { text-decoration: underline; }
.drawer-heading { width: 100%; display: flex; align-items: center; justify-content: space-between; gap: 16px; }
.drawer-heading h2 { color: #203149; font-size: 20px; }
.drawer-heading p { margin-top: 6px; color: #8b99ad; font-size: 12px; }
.detail-body { display: flex; flex-direction: column; gap: 18px; min-height: 240px; }
.detail-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 16px; padding: 16px 18px; background: #f6f9ff; border: 1px solid #e4ecfb; border-radius: 10px; }
.detail-toolbar strong { color: #263a56; }
.detail-toolbar p { margin-top: 5px; color: #8b99ad; font-size: 12px; }
.detail-descriptions :deep(.el-descriptions__label) { width: 105px; }
.detail-section { border-top: 1px solid #edf1f6; padding-top: 20px; }
.section-title { display: flex; align-items: flex-start; justify-content: space-between; gap: 14px; margin-bottom: 14px; }
.section-title h3 { color: #26364d; font-size: 16px; }
.section-title p { margin-top: 5px; color: #8e9caf; font-size: 12px; }
.detail-actions { display: flex; justify-content: flex-end; gap: 10px; padding-top: 6px; }
@media (max-width: 760px) {
  .pharmacy-summary { grid-template-columns: 1fr; }
  .toolbar-left, .toolbar-right, .pharmacy-search { width: 100% !important; }
  .detail-toolbar { align-items: flex-start; flex-direction: column; }
  .detail-descriptions { --el-descriptions-table-border: 1px solid #e5ebf3; }
  .detail-descriptions :deep(.el-descriptions__body .el-descriptions__table) { min-width: 560px; }
  .detail-descriptions { overflow-x: auto; }
}
</style>
