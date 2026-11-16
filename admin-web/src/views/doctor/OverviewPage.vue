<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { Calendar, Clock, CircleCheck, Refresh } from '@element-plus/icons-vue'
import PageHeading from '../../components/PageHeading.vue'
import UserAvatar from '../../components/UserAvatar.vue'
import { api } from '../../lib/api'
import { useStatusRefresh } from '../../lib/useStatusRefresh'

const overview = ref(null)
const loading = ref(false)
const error = ref('')
let requestSequence = 0

const statItems = [
  { key: 'todayAppointments', label: '今日预约', hint: '不含已取消预约', icon: Calendar, tone: 'blue' },
  { key: 'pendingAppointments', label: '今日待诊', hint: '待就诊、已确认及已到院', icon: Clock, tone: 'orange' },
  { key: 'completedAppointments', label: '累计完成', hint: '已完成的诊疗预约', icon: CircleCheck, tone: 'green' },
]

async function loadOverview(options = {}) {
  const background = options?.background === true
  if (background && loading.value) return
  const current = ++requestSequence
  if (!background) { loading.value = true; error.value = '' }
  try {
    const data = await api.get('/doctor-workspace/overview', { quiet: background })
    if (current === requestSequence) { overview.value = data || null; error.value = '' }
  } catch (failure) {
    if (current === requestSequence) {
      if (!background) overview.value = null
      error.value = background ? `统计同步失败，当前显示上次结果：${failure.message}` : failure.message
    }
  } finally {
    if (current === requestSequence) loading.value = false
  }
}

onMounted(loadOverview)
useStatusRefresh(() => loadOverview({ background: true }))
onBeforeUnmount(() => requestSequence++)
</script>

<template>
  <section class="page-stack doctor-overview">
    <PageHeading title="医生工作台" description="集中查看本人资料、今日接诊进度与累计完成情况">
      <el-button :icon="Refresh" :loading="loading" @click="loadOverview">刷新</el-button>
    </PageHeading>

    <el-alert v-if="error" :title="error" type="error" show-icon :closable="false">
      <template #default><el-button link type="primary" @click="loadOverview">重新加载</el-button></template>
    </el-alert>

    <div v-loading="loading" class="overview-content">
      <template v-if="overview">
        <article class="page-panel profile-card">
          <UserAvatar :src="overview.doctor?.avatar" :name="overview.doctor?.name" :size="76" />
          <div class="profile-main">
            <div class="profile-title-row">
              <h2>{{ overview.doctor?.name || '未设置姓名' }}</h2>
              <el-tag v-if="overview.doctor?.title" effect="plain" round>{{ overview.doctor.title }}</el-tag>
            </div>
            <p class="profile-department">
              {{ overview.doctor?.departmentName || '未分配科室' }}
            </p>
            <p class="profile-intro">
              {{ overview.doctor?.intro?.trim() || '暂未填写医生简介' }}
            </p>
          </div>
        </article>

        <div class="stat-grid doctor-stats">
          <article v-for="item in statItems" :key="item.key" class="stat-card doctor-stat">
            <div class="stat-icon" :class="`is-${item.tone}`">
              <el-icon><component :is="item.icon" /></el-icon>
            </div>
            <div class="stat-copy">
              <span>{{ item.label }}</span>
              <strong>{{ Number(overview[item.key] || 0) }}</strong>
              <small>{{ item.hint }}</small>
            </div>
          </article>
        </div>
      </template>

      <div v-else-if="!loading && !error" class="page-panel">
        <el-empty description="暂未获取到医生工作台数据">
          <el-button type="primary" @click="loadOverview">重新加载</el-button>
        </el-empty>
      </div>
    </div>
  </section>
</template>

<style scoped>
.doctor-overview { gap: 18px; }
.overview-content { min-height: 220px; }
.profile-card { display: flex; align-items: flex-start; gap: 20px; padding: 24px; overflow: hidden; position: relative; }
.profile-card::after { content: ''; position: absolute; right: -42px; top: -58px; width: 190px; height: 190px; border-radius: 50%; background: linear-gradient(145deg, rgba(64, 158, 255, .16), rgba(64, 158, 255, 0)); pointer-events: none; }
.profile-main { min-width: 0; flex: 1; }
.profile-title-row { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.profile-title-row h2 { margin: 2px 0 0; color: #1f2d3d; font-size: 23px; }
.profile-department { margin: 6px 0 10px; color: #409eff; font-weight: 600; }
.profile-intro { max-width: 780px; margin: 0; color: #606b78; line-height: 1.75; white-space: pre-wrap; }
.doctor-stats { grid-template-columns: repeat(3, minmax(0, 1fr)); margin-top: 18px; }
.doctor-stat { display: flex; align-items: center; gap: 18px; min-height: 132px; }
.doctor-stat .stat-icon { width: 54px; height: 54px; flex: 0 0 54px; border-radius: 17px; }
.doctor-stat .stat-icon.is-blue { color: #3b82f6; background: #eaf2ff; }
.doctor-stat .stat-icon.is-orange { color: #e58b28; background: #fff4e7; }
.doctor-stat .stat-icon.is-green { color: #25a56a; background: #eaf8f1; }
.stat-copy { display: flex; flex-direction: column; min-width: 0; }
.stat-copy span { color: #596574; font-weight: 600; }
.stat-copy strong { margin: 4px 0 2px; color: #172537; font-size: 30px; line-height: 1.15; }
.stat-copy small { color: #98a2af; }
@media (max-width: 760px) {
  .profile-card { padding: 18px; gap: 14px; }
  .profile-card :deep(.el-avatar) { width: 58px !important; height: 58px !important; }
  .profile-title-row h2 { font-size: 20px; }
  .doctor-stats { grid-template-columns: 1fr; margin-top: 12px; }
  .doctor-stat { min-height: 108px; }
}
</style>
