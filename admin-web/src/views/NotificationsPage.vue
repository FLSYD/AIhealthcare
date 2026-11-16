<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Bell, Check, Refresh } from '@element-plus/icons-vue'
import { auth } from '../store/auth'
import { api, runAction } from '../lib/api'
import { dateTime } from '../lib/format'
import { usePagedList } from '../lib/usePagedList'
import { getNotificationTarget, notificationTypeText } from '../lib/notificationRoute'
import PageHeading from '../components/PageHeading.vue'
import DataPagination from '../components/DataPagination.vue'
const router = useRouter(), busy = ref(false)
const isPharmacist = computed(() => auth.user?.role === 'PHARMACIST')
const description = computed(() => isPharmacist.value ? '查看处方缴费、发药与配药相关的消息' : '查看预约、排班与诊疗相关的消息')
const targetOf = item => getNotificationTarget(item, auth.user?.role)
const { rows, total, page, size, filters, loading, error, load, search } = usePagedList('/notifications', { unreadOnly: false })
async function read(item) {
  if (!item.read) { const result = await runAction(() => api.put(`/notifications/${item.id}/read`), ''); if (result.ok) { item.read = true; window.dispatchEvent(new Event('notifications-read')); if (filters.unreadOnly) await load() } }
}
async function open(item) {
  await read(item)
  // 跳转目标由纯函数按角色计算；处方通知带 id（已发药通知带 status），精确打开对应处方
  const target = targetOf(item)
  if (target) router.push(target)
}
async function readAll() { busy.value = true; try { const result = await runAction(() => api.put('/notifications/read-all'), '已将全部消息标记为已读'); if (result.ok) { window.dispatchEvent(new Event('notifications-read')); await load() } } finally { busy.value = false } }
onMounted(load)
</script>
<template><div><PageHeading title="消息通知" :description="description"><el-button :icon="Refresh" @click="load">刷新</el-button><el-button type="primary" :icon="Check" :loading="busy" @click="readAll">全部已读</el-button></PageHeading><section class="page-panel" v-loading="loading"><div class="table-toolbar"><el-radio-group v-model="filters.unreadOnly" @change="search"><el-radio-button :value="false">全部消息</el-radio-button><el-radio-button :value="true">未读消息</el-radio-button></el-radio-group><span class="muted text-small">共 {{ total }} 条消息</span></div><el-alert v-if="error" :title="error" type="error" :closable="false" show-icon class="empty-error" /><el-empty v-if="!rows.length && !loading" description="暂无消息通知" :image-size="100" /><div v-else class="notifications-list"><article v-for="item in rows" :key="item.id" class="notification-item" :class="{ unread: !item.read }"><div class="notification-icon"><el-icon><Bell /></el-icon></div><div class="notification-content"><div class="notification-title"><strong>{{ item.title }}</strong><el-tag v-if="!item.read" size="small" round>未读</el-tag></div><p>{{ item.content }}</p><div class="notification-meta"><span>{{ notificationTypeText(item) }}</span><time>{{ dateTime(item.createTime) }}</time></div></div><div class="notification-actions"><el-button v-if="!item.read" link @click="read(item)">标为已读</el-button><el-button v-if="targetOf(item)" type="primary" link @click="open(item)">{{ item.bizType === 'prescription' ? '查看处方' : '查看详情' }}</el-button></div></article></div><DataPagination v-model:page="page" v-model:size="size" :total="total" @change="load" /></section></div></template>
<style scoped>.notification-item{display:flex;align-items:flex-start;gap:15px;padding:21px 12px;border-bottom:1px solid #edf1f6}.notification-item:last-child{border:0}.notification-icon{width:38px;height:38px;background:#f2f5f9;color:#99a8ba;border-radius:11px;display:flex;align-items:center;justify-content:center;font-size:19px;flex-shrink:0}.unread .notification-icon{background:#edf3ff;color:#4a7ff0}.notification-content{flex:1;min-width:0}.notification-title{display:flex;align-items:center;gap:12px}.notification-title strong{font-size:14px;font-weight:600;color:#32425a}.notification-content p{margin-top:10px;font-size:13px;color:#8795a9;line-height:1.8}.notification-meta{display:flex;gap:16px;color:#a3afbf;font-size:11px;margin-top:12px}.notification-actions{display:flex;gap:15px;padding-top:5px;white-space:nowrap}@media(max-width:650px){.notification-item{padding:18px 0;gap:11px;flex-wrap:wrap}.notification-actions{padding-left:49px;width:100%}.notification-meta{flex-wrap:wrap;gap:7px}}</style>
