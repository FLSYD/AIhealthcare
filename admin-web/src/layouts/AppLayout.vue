<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { isNavigationFailure, useRoute, useRouter } from 'vue-router'
import { Avatar, Bell, Calendar, DataAnalysis, Document, FirstAidKit, Grid, House, Menu, OfficeBuilding, Tickets, User, ArrowDown, SwitchButton, Lock, Refresh } from '@element-plus/icons-vue'
import { auth, signOut } from '../store/auth'
import { canRoleAccess, pageRegistry } from '../router'
import { api, confirmAction } from '../lib/api'
import { roleLabel } from '../lib/format'
import UserAvatar from '../components/UserAvatar.vue'
import { confirmNavigation } from '../lib/navigation'

const route = useRoute(), router = useRouter()
const collapsed = ref(false), unread = ref(0), tabs = ref([]), viewKey = ref(0)
const icons = { Avatar, Calendar, DataAnalysis, Document, FirstAidKit, Grid, House, OfficeBuilding, Tickets, User }
const menus = computed(() => auth.menus.filter(menu =>
  pageRegistry[menu.path] && canRoleAccess(pageRegistry[menu.path].roles, auth.user?.role)))
const home = computed(() => auth.permissions?.homePath || '/dashboard')
const canRenderPage = computed(() => auth.ready && canRoleAccess(route.meta.roles, auth.user?.role))
const displayName = computed(() => auth.user?.nickname || auth.user?.username || '')
const workspaceCaption = computed(() => ({
  ADMIN: '管理工作台',
  DOCTOR: '医生工作台',
  PHARMACIST: '配药工作台',
}[auth.user?.role] || '工作空间'))
const roleTagType = computed(() => ({
  ADMIN: 'danger',
  DOCTOR: 'primary',
  PHARMACIST: 'warning',
}[auth.user?.role] || 'info'))
function addTab() {
  if (!route.meta.title || route.path === '/login') return
  if (!canRoleAccess(route.meta.roles, auth.user?.role)) return
  if (!tabs.value.some(item => item.path === home.value)) tabs.value.unshift({ path: home.value, title: pageRegistry[home.value]?.title || '首页' })
  if (!tabs.value.some(item => item.path === route.path)) tabs.value.push({ path: route.path, title: route.meta.title })
  if (window.innerWidth < 900) collapsed.value = true
}
watch(() => route.path, () => { addTab(); fetchUnread() }, { immediate: true })
watch(() => `${auth.user?.id}:${auth.user?.role}`, () => { tabs.value = []; unread.value = 0; if (auth.ready) addTab() })
async function closeTab(path) {
  const index = tabs.value.findIndex(tab => tab.path === path)
  if (route.path === path) {
    const remaining = tabs.value.filter(tab => tab.path !== path)
    const failure = await router.push(remaining[Math.max(0, index - 1)]?.path || home.value)
    if (isNavigationFailure(failure)) return
  }
  tabs.value = tabs.value.filter(tab => tab.path !== path)
}
async function fetchUnread() { try { unread.value = Number(await api.get('/notifications/unread-count')) } catch {} }
async function command(value) {
  if (value === 'logout') {
    if (!await confirmNavigation()) return
    if (!await confirmAction('确定退出当前账号吗？', '退出登录')) return
    try { await signOut() } catch {} finally { tabs.value = []; router.replace('/login') }
  } else router.push(value)
}
async function refresh() { if (await confirmNavigation()) { viewKey.value++; fetchUnread() } }
let timer
onMounted(() => { fetchUnread(); timer = setInterval(fetchUnread, 60000); window.addEventListener('notifications-read', fetchUnread) })
onUnmounted(() => { clearInterval(timer); window.removeEventListener('notifications-read', fetchUnread) })
</script>
<template>
  <div class="app-shell" :class="{ 'sidebar-collapsed': collapsed }">
    <aside class="sidebar">
      <router-link :to="home" class="brand"><span class="brand-mark">✚</span><span class="brand-name">医院预约挂号管理<small>HOSPITAL MANAGEMENT</small></span></router-link>
      <div class="sidebar-caption">{{ workspaceCaption }}</div>
      <nav class="side-nav" aria-label="主导航">
        <router-link v-for="item in menus" :key="item.path" :to="item.path" :title="item.name" class="side-link" :class="{ active: route.path === item.path }">
          <el-icon><component :is="icons[pageRegistry[item.path]?.icon] || Grid" /></el-icon><span>{{ item.name }}</span>
        </router-link>
      </nav>
      <div class="sidebar-bottom"><span class="online-dot"></span><span>医院管理工作台</span></div>
    </aside>
    <button v-if="!collapsed" class="sidebar-backdrop" aria-label="收起菜单" @click="collapsed = true"></button>
    <div class="workspace">
      <header class="topbar">
        <div class="header-left"><button class="icon-button menu-toggle" aria-label="展开或收起菜单" @click="collapsed = !collapsed"><el-icon><Menu /></el-icon></button><div class="breadcrumb"><strong>后台</strong><span>/</span><span>{{ route.meta.title }}</span></div></div>
        <div class="header-right">
          <el-tooltip content="消息通知"><router-link to="/notifications" class="icon-button notification-link" aria-label="消息通知"><el-badge :value="unread" :hidden="!unread" :max="99"><el-icon><Bell /></el-icon></el-badge></router-link></el-tooltip>
          <el-tag :type="roleTagType" effect="plain" round size="small">{{ roleLabel(auth.user?.role) }}</el-tag>
          <el-dropdown trigger="click" @command="command"><button class="account-button"><span class="account-name">{{ displayName }}</span><UserAvatar :src="auth.user?.avatar" :name="displayName" :size="38" /><el-icon class="account-chevron"><ArrowDown /></el-icon></button><template #dropdown><el-dropdown-menu><el-dropdown-item command="/profile" :icon="User">个人信息</el-dropdown-item><el-dropdown-item command="/password" :icon="Lock">修改密码</el-dropdown-item><el-dropdown-item command="logout" :icon="SwitchButton" divided>退出登录</el-dropdown-item></el-dropdown-menu></template></el-dropdown>
        </div>
      </header>
      <div class="tabbar"><div class="workspace-tabs"><button v-for="tab in tabs" :key="tab.path" class="workspace-tab" :class="{ active: route.path === tab.path }" @click="router.push(tab.path)"><span>{{ tab.title }}</span><span v-if="tab.path !== home" class="tab-close" role="button" :aria-label="'关闭' + tab.title" @click.stop="closeTab(tab.path)">×</span></button></div><el-tooltip content="刷新当前页面"><button class="icon-button refresh-button" aria-label="刷新当前页面" @click="refresh"><el-icon><Refresh /></el-icon></button></el-tooltip></div>
      <main class="main-content"><router-view v-if="canRenderPage" :key="route.path + viewKey" /></main>
      <footer class="workspace-footer">医院预约挂号管理系统 <span>·</span> 让就诊管理更有序</footer>
    </div>
  </div>
</template>
