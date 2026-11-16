<script setup>
import { onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Refresh, Search } from '@element-plus/icons-vue'
import PageHeading from '../../components/PageHeading.vue'
import DataPagination from '../../components/DataPagination.vue'
import StatusTag from '../../components/StatusTag.vue'
import ClinicalDrawer from '../../components/ClinicalDrawer.vue'
import { appointmentOptions, visitTime } from '../../lib/format'
import { useAppointmentList } from '../../lib/useAppointmentList'
import { useStatusRefresh } from '../../lib/useStatusRefresh'

const drawerVisible = ref(false)
const currentAppointmentId = ref(null)
const route = useRoute()
const router = useRouter()
watch(() => route.query.id, id => {
  if (/^[1-9]\d*$/.test(String(id || ''))) {
    currentAppointmentId.value = id
    drawerVisible.value = true
  } else { currentAppointmentId.value = null; drawerVisible.value = false }
}, { immediate: true })
const paged = useAppointmentList('/doctor-workspace/appointments', {
  keyword: '',
  status: '',
  scheduleDate: undefined,
})
const { filters, rows, total, page, size, loading, error, load } = paged
useStatusRefresh(() => load({ background: true }), { enabled: () => !drawerVisible.value && !loading.value })

function clearFilters() {
  filters.keyword = ''
  filters.status = ''
  filters.scheduleDate = undefined
}

function openConsultation(row) {
  router.push({ path: route.path, query: { ...route.query, id: String(row.id) } })
}

function setDrawerVisible(value) {
  drawerVisible.value = value
  if (!value && route.query.id !== undefined) {
    const query = { ...route.query }
    delete query.id
    router.replace({ path: route.path, query })
  }
}

function entryLabel(row) {
  return ['completed', 'cancelled'].includes(row.status) ? '查看' : '诊疗'
}

function refreshAfterChange() {
  load({ background: true })
}

onMounted(load)
</script>

<template>
  <section class="page-stack">
    <PageHeading title="我的预约" description="查询本人接诊范围内的就诊人与预约状态；处方缴费后由配药师完成发药">
      <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
    </PageHeading>

    <div class="page-panel">
      <div class="table-toolbar appointment-toolbar">
        <div class="toolbar-left">
          <el-input
            v-model="filters.keyword"
            clearable
            placeholder="姓名或手机号"
            class="filter-input"
            @keyup.enter="paged.search"
          />
          <el-select v-model="filters.status" clearable placeholder="预约状态" class="status-filter">
            <el-option v-for="item in appointmentOptions" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
          <el-date-picker
            v-model="filters.scheduleDate"
            type="date"
            value-format="YYYY-MM-DD"
            placeholder="就诊日期"
            clearable
            class="date-filter"
          />
        </div>
        <div class="toolbar-right">
          <el-button :icon="Search" type="primary" :loading="loading" @click="paged.search">查询</el-button>
          <el-button @click="clearFilters(); paged.reset()">重置</el-button>
        </div>
      </div>

      <el-alert v-if="error" :title="error" type="error" show-icon :closable="false">
        <template #default><el-button link type="primary" @click="load">重新加载</el-button></template>
      </el-alert>

      <div class="table-scroll-x">
        <el-table v-loading="loading" :data="rows" row-key="id" stripe>
          <el-table-column prop="patientName" label="就诊人" min-width="120" show-overflow-tooltip />
          <el-table-column prop="patientPhone" label="联系电话" min-width="140">
            <template #default="{ row }">
              <a v-if="row.patientPhone" :href="`tel:${row.patientPhone}`">{{ row.patientPhone }}</a>
              <span v-else class="muted">—</span>
            </template>
          </el-table-column>
          <el-table-column label="就诊时间" min-width="190">
            <template #default="{ row }">{{ visitTime(row) }}</template>
          </el-table-column>
          <el-table-column label="预约状态" min-width="110" align="center">
            <template #default="{ row }"><StatusTag :status="row.status" :row="row" kind="appointment" /></template>
          </el-table-column>
          <el-table-column label="处方状态" min-width="110" align="center">
            <template #default="{ row }">
              <StatusTag v-if="row.prescriptionStatus" :status="row.prescriptionStatus" kind="prescription" />
              <span v-else class="muted">无处方</span>
            </template>
          </el-table-column>
          <el-table-column prop="departmentName" label="科室" min-width="130" show-overflow-tooltip />
          <el-table-column label="操作" width="112" fixed="right" align="center">
            <template #default="{ row }">
              <div class="row-actions">
                <el-button link type="primary" @click="openConsultation(row)">{{ entryLabel(row) }}</el-button>
              </div>
            </template>
          </el-table-column>
          <template #empty>
            <el-empty v-if="!loading && !error" description="暂无符合条件的预约" />
          </template>
        </el-table>
      </div>

      <DataPagination v-model:page="page" v-model:size="size" :total="total" @change="load" />
    </div>

    <ClinicalDrawer
      :model-value="drawerVisible"
      @update:model-value="setDrawerVisible"
      :appointment-id="currentAppointmentId"
      @changed="refreshAfterChange"
    />
  </section>
</template>

<style scoped>
.appointment-toolbar { align-items: center; }
.status-filter { width: 160px; }
.date-filter { width: 170px; }
.table-scroll-x { overflow-x: auto; }
.table-scroll-x :deep(.el-table) { min-width: 1040px; }
a { color: #337ecc; text-decoration: none; }
a:hover { text-decoration: underline; }
@media (max-width: 760px) {
  .appointment-toolbar { align-items: stretch; }
  .toolbar-left { display: grid; grid-template-columns: 1fr; width: 100%; }
  .filter-input, .status-filter, .date-filter { width: 100%; }
  .toolbar-right { justify-content: flex-end; }
}
</style>
