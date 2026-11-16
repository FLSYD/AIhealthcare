<script setup>
import { onMounted, ref } from 'vue'
import { Refresh, Search } from '@element-plus/icons-vue'
import PageHeading from '../../components/PageHeading.vue'
import DataPagination from '../../components/DataPagination.vue'
import StatusTag from '../../components/StatusTag.vue'
import { usePagedList } from '../../lib/usePagedList'

const dateRange = ref([])
const filterError = ref('')
const appliedDateRange = ref([])
const paged = usePagedList('/doctor-workspace/schedules', {
  startDate: undefined,
  endDate: undefined,
})
const { filters, rows, total, page, size, loading, error, load, search } = paged

async function applyFilters() {
  filterError.value = ''
  filters.startDate = dateRange.value?.[0] || undefined
  filters.endDate = dateRange.value?.[1] || undefined
  if (filters.startDate && filters.endDate && filters.startDate > filters.endDate) {
    filterError.value = '开始日期不能晚于结束日期'
    return
  }
  appliedDateRange.value = Array.isArray(dateRange.value) ? [...dateRange.value] : []
  await search()
}

async function resetFilters() {
  filterError.value = ''
  dateRange.value = []
  appliedDateRange.value = []
  await paged.reset()
}

onMounted(() => {
  filters.startDate = appliedDateRange.value?.[0] || undefined
  filters.endDate = appliedDateRange.value?.[1] || undefined
  load()
})
</script>

<template>
  <section class="page-stack">
    <PageHeading title="我的排班" description="按日期查看本人排班、号源使用情况与开放状态">
      <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
    </PageHeading>

    <div class="page-panel">
      <div class="table-toolbar schedule-toolbar">
        <div class="toolbar-left">
          <el-date-picker
            v-model="dateRange"
            type="daterange"
            value-format="YYYY-MM-DD"
            range-separator="至"
            start-placeholder="开始日期"
            end-placeholder="结束日期"
            clearable
            class="schedule-range"
            @change="applyFilters"
          />
        </div>
        <div class="toolbar-right">
          <el-button :icon="Search" type="primary" :loading="loading" @click="applyFilters">查询</el-button>
          <el-button @click="resetFilters">重置</el-button>
        </div>
      </div>

      <el-alert v-if="filterError" :title="filterError" type="warning" show-icon :closable="false" />
      <el-alert v-if="error" :title="error" type="error" show-icon :closable="false">
        <template #default><el-button link type="primary" @click="load">重新加载</el-button></template>
      </el-alert>

      <div class="table-scroll-x">
        <el-table v-loading="loading" :data="rows" row-key="id" stripe>
          <el-table-column prop="scheduleDate" label="排班日期" min-width="128" />
          <el-table-column prop="timeSlot" label="时段" min-width="130" />
          <el-table-column prop="departmentName" label="科室" min-width="140" show-overflow-tooltip />
          <el-table-column label="号源" min-width="150">
            <template #default="{ row }">
              <span>{{ Number(row.bookedCount || 0) }} / {{ Number(row.totalQuota || 0) }}</span>
              <span class="muted quota-remain">余 {{ Math.max(Number(row.totalQuota || 0) - Number(row.bookedCount || 0), 0) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="100" align="center">
            <template #default="{ row }"><StatusTag :status="row.status" kind="schedule" /></template>
          </el-table-column>
          <el-table-column prop="updateTime" label="更新时间" min-width="170" />
          <template #empty>
            <el-empty v-if="!loading && !error" description="暂无符合条件的排班" />
          </template>
        </el-table>
      </div>

      <DataPagination v-model:page="page" v-model:size="size" :total="total" @change="load" />
    </div>
  </section>
</template>

<style scoped>
.schedule-toolbar { align-items: center; }
.schedule-range { width: min(360px, 100%); }
.table-scroll-x { overflow-x: auto; }
.table-scroll-x :deep(.el-table) { min-width: 820px; }
.quota-remain { margin-left: 10px; font-size: 12px; }
@media (max-width: 720px) {
  .schedule-toolbar { align-items: stretch; }
  .toolbar-left, .schedule-range { width: 100%; }
  .toolbar-right { justify-content: flex-end; }
}
</style>
