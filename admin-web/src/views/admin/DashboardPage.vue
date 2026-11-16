<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { Calendar, Tickets, Avatar, User, Refresh, ArrowRight } from '@element-plus/icons-vue'
import { api } from '../../lib/api'
import { auth } from '../../store/auth'
import ChartPanel from '../../components/ChartPanel.vue'
import { useStatusRefresh } from '../../lib/useStatusRefresh'
const stats = ref(null), loading = ref(false), error = ref(''), updated = ref('')
let requestSequence = 0
const today = new Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Hong_Kong', year: 'numeric', month: 'long', day: 'numeric', weekday: 'long' }).format(new Date())
const cards = computed(() => [
  { label: '今日预约', value: stats.value?.todayCount, icon: Calendar, color: '#4a7df0', background: '#edf3ff', note: '今日就诊预约数量' },
  { label: '累计预约', value: stats.value?.totalAppointments, icon: Tickets, color: '#29ac99', background: '#eaf9f5', note: '全部预约记录' },
  { label: '在岗医生', value: stats.value?.totalDoctors, icon: Avatar, color: '#ecaa52', background: '#fff7ed', note: '当前启用的医生' },
  { label: '注册用户', value: stats.value?.totalUsers, icon: User, color: '#8d79d7', background: '#f3efff', note: '患者用户总数' },
])
const palette = ['#4d80f5', '#67bcb0', '#f0bd70', '#a897e5', '#ef92a0', '#87b7e5', '#89c895', '#a9b7cf', '#c6d1e2']
const baseText = { fontFamily: 'Microsoft YaHei, sans-serif', color: '#8a99ae', fontSize: 11 }
const trend = computed(() => ({ textStyle: baseText, tooltip: { trigger: 'axis' }, grid: { top: 20, right: 22, bottom: 28, left: 40 }, xAxis: { type: 'category', boundaryGap: false, data: stats.value?.appointmentTrend?.map(x => x.name) || [], axisLine: { lineStyle: { color: '#e8eef5' } }, axisTick: { show: false }, axisLabel: { margin: 15 } }, yAxis: { type: 'value', minInterval: 1, splitLine: { lineStyle: { color: '#eff3f8', type: 'dashed' } } }, series: [{ name: '预约数', type: 'line', smooth: true, symbolSize: 7, data: stats.value?.appointmentTrend?.map(x => x.value) || [], itemStyle: { color: '#4b80f5', borderColor: '#fff', borderWidth: 2 }, lineStyle: { width: 3 }, areaStyle: { color: { type: 'linear', x: 0, y: 0, x2: 0, y2: 1, colorStops: [{ offset: 0, color: '#6b9af42b' }, { offset: 1, color: '#f5f8ff10' }] } } }] }))
const distribution = computed(() => ({ textStyle: baseText, color: palette, tooltip: { trigger: 'item' }, legend: { bottom: 0, left: 'center', icon: 'circle', itemWidth: 7, itemHeight: 7, textStyle: { fontSize: 10, color: '#8493a8' }, itemGap: 15 }, series: [{ name: '预约状态', type: 'pie', radius: ['48%', '69%'], center: ['50%', '43%'], itemStyle: { borderRadius: 4, borderColor: '#fff', borderWidth: 3 }, label: { show: false }, emphasis: { label: { show: true, formatter: '{b}\n{c}笔', color: '#45617f', fontSize: 13 } }, data: (stats.value?.statusDistribution || []).filter(x => x.value > 0) }] }))
const department = computed(() => ({ textStyle: baseText, tooltip: { trigger: 'axis' }, grid: { top: 12, left: 75, right: 30, bottom: 23 }, xAxis: { type: 'value', minInterval: 1, splitLine: { lineStyle: { color: '#eff3f8', type: 'dashed' } } }, yAxis: { type: 'category', inverse: true, data: stats.value?.departmentTop?.map(x => x.name) || [], axisLine: { show: false }, axisTick: { show: false } }, series: [{ name: '预约数', type: 'bar', data: stats.value?.departmentTop?.map(x => x.value) || [], barMaxWidth: 14, itemStyle: { color: '#6390ee', borderRadius: [0, 4, 4, 0] }, label: { show: true, position: 'right', color: '#91a1b9', fontSize: 10 } }] }))
const rating = computed(() => ({ textStyle: baseText, tooltip: { trigger: 'axis' }, grid: { top: 12, left: 37, right: 25, bottom: 27 }, xAxis: { type: 'category', data: stats.value?.ratingDistribution?.map(x => x.name) || [], axisLine: { lineStyle: { color: '#e8eef5' } }, axisTick: { show: false } }, yAxis: { type: 'value', minInterval: 1, splitLine: { lineStyle: { color: '#eff3f8', type: 'dashed' } } }, series: [{ name: '评价数', type: 'bar', barMaxWidth: 24, data: stats.value?.ratingDistribution?.map(x => x.value) || [], itemStyle: { color: '#70bfae', borderRadius: [5, 5, 0, 0] } }] }))
async function load(options = {}) {
  const background = options?.background === true
  if (background && loading.value) return
  const current = ++requestSequence
  if (!background) { loading.value = true; error.value = '' }
  try {
    const result = await api.get('/dashboard/stats', { quiet: background })
    if (current === requestSequence) {
      stats.value = result
      error.value = ''
      updated.value = new Date().toLocaleTimeString('zh-CN', { hour12: false })
    }
  } catch (failure) {
    if (current === requestSequence) error.value = background ? `统计同步失败，当前显示上次结果：${failure.message}` : failure.message
  } finally { if (current === requestSequence) loading.value = false }
}
useStatusRefresh(() => load({ background: true }))
onBeforeUnmount(() => requestSequence++)
onMounted(load)
</script>
<template><div class="page-stack" v-loading="loading"><div class="dashboard-welcome"><div><div class="welcome-date">{{ today }}</div><h1>{{ auth.user?.nickname || '管理员' }}，欢迎回来</h1><p>工作井然有序，服务温暖如初。</p></div><el-button :icon="Refresh" @click="load">刷新数据</el-button></div><el-alert v-if="error" :title="error" type="error" :closable="false" show-icon /><div class="stat-grid"><article v-for="card in cards" :key="card.label" class="stat-card"><div class="stat-icon" :style="{ color: card.color, background: card.background }"><el-icon><component :is="card.icon" /></el-icon></div><div><div class="stat-label">{{ card.label }}</div><div class="stat-value">{{ card.value ?? '—' }}</div><div class="stat-note">{{ card.note }}</div></div></article></div><div class="dashboard-chart-grid"><section class="page-panel trend-panel"><div class="panel-title"><h2>近7日预约趋势</h2><span>每日预约量</span></div><ChartPanel v-if="stats" :option="trend" :height="280" /><el-empty v-else description="暂无统计数据" :image-size="70" /></section><section class="page-panel"><div class="panel-title"><h2>预约状态分布</h2><router-link to="/appointment" class="chart-link">查看预约 <el-icon><ArrowRight /></el-icon></router-link></div><ChartPanel v-if="stats?.statusDistribution?.some(item => item.value > 0)" :option="distribution" :height="280" /><el-empty v-else description="暂无预约记录" :image-size="70" /></section></div><div class="dashboard-chart-grid bottom-charts"><section class="page-panel"><div class="panel-title"><h2>热门科室</h2><span>按预约数量统计</span></div><ChartPanel v-if="stats?.departmentTop?.length" :option="department" :height="245" /><el-empty v-else description="暂无科室预约数据" :image-size="70" /></section><section class="page-panel"><div class="panel-title"><h2>就诊评价</h2><span>患者评价星级分布</span></div><ChartPanel v-if="stats" :option="rating" :height="245" /><el-empty v-else description="暂无评价数据" :image-size="70" /></section></div><div class="dashboard-updated">{{ updated ? `数据更新于 ${updated}` : '统计数据来自当前医院记录' }}</div></div></template>
<style scoped>.dashboard-welcome{display:flex;align-items:center;justify-content:space-between;gap:18px;margin-bottom:2px}.welcome-date{font-size:11px;color:#94a5bb;letter-spacing:.5px;margin-bottom:9px}.dashboard-welcome h1{font-size:22px;font-weight:650;color:#20334d}.dashboard-welcome p{font-size:12px;color:#96a4b7;margin-top:10px}.dashboard-chart-grid{display:grid;grid-template-columns:minmax(0,1.55fr) minmax(0,1fr);gap:20px}.bottom-charts{grid-template-columns:1fr 1fr}.chart-link{font-size:11px;color:#8c9eb6;display:flex;align-items:center;gap:4px}.chart-link:hover{color:#2862ee}.dashboard-updated{font-size:10px;color:#a7b3c3;text-align:right;padding:0 3px}@media(max-width:1000px){.dashboard-chart-grid{grid-template-columns:1fr 1fr}.dashboard-welcome h1{font-size:20px}}@media(max-width:650px){.dashboard-chart-grid{grid-template-columns:1fr}.dashboard-welcome{align-items:flex-start}.dashboard-welcome h1{font-size:18px}.welcome-date{font-size:10px}.dashboard-welcome .el-button{padding:9px 11px;font-size:12px}}</style>
