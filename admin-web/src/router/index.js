import { createRouter, createWebHistory } from 'vue-router'
import { auth, loadSession } from '../store/auth'
import { TOKEN_KEY } from '../lib/api'

export const pageRegistry = {
  '/dashboard': { title: '数据概览', roles: ['ADMIN'], icon: 'DataAnalysis', component: () => import('../views/admin/DashboardPage.vue') },
  '/department': { title: '科室管理', roles: ['ADMIN'], icon: 'OfficeBuilding', component: () => import('../views/admin/DepartmentsPage.vue') },
  '/doctor': { title: '医生管理', roles: ['ADMIN'], icon: 'Avatar', component: () => import('../views/admin/DoctorsPage.vue') },
  '/schedule': { title: '排班管理', roles: ['ADMIN'], icon: 'Calendar', component: () => import('../views/admin/SchedulesPage.vue') },
  '/medicine': { title: '药品管理', roles: ['ADMIN', 'PHARMACIST'], icon: 'FirstAidKit', component: () => import('../views/admin/MedicinesPage.vue') },
  '/sysUser': { title: '用户管理', roles: ['ADMIN'], icon: 'User', component: () => import('../views/admin/UsersPage.vue') },
  '/sys-login-log': { title: '系统日志', roles: ['ADMIN'], icon: 'Document', component: () => import('../views/admin/LogsPage.vue') },
  '/menu': { title: '菜单管理', roles: ['ADMIN'], icon: 'Grid', component: () => import('../views/admin/MenusPage.vue') },
  '/appointment': { title: '预约管理', roles: ['ADMIN'], icon: 'Tickets', component: () => import('../views/admin/AppointmentsPage.vue') },
  '/doctor/overview': { title: '工作概览', roles: ['DOCTOR'], icon: 'House', component: () => import('../views/doctor/OverviewPage.vue') },
  '/doctor/schedules': { title: '我的排班', roles: ['DOCTOR'], icon: 'Calendar', component: () => import('../views/doctor/SchedulesPage.vue') },
  '/doctor/appointments': { title: '我的预约', roles: ['DOCTOR'], icon: 'Tickets', component: () => import('../views/doctor/AppointmentsPage.vue') },
  '/pharmacy': { title: '配药工作台', roles: ['PHARMACIST'], icon: 'FirstAidKit', component: () => import('../views/pharmacy/PharmacyWorkbenchPage.vue') },
}

export const canRoleAccess = (roles, role) =>
  !Array.isArray(roles) || roles.length === 0 || roles.includes(role)

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', component: () => import('../views/LoginPage.vue'), meta: { public: true, title: '登录' } },
    { path: '/cashier/:token', component: () => import('../views/NativeCashierPage.vue'), meta: { public: true, cashier: true, title: '微信扫码支付' } },
    { path: '/', component: () => import('../layouts/AppLayout.vue'), children: [
      { path: '', redirect: () => auth.permissions?.homePath || '/login' },
      ...Object.entries(pageRegistry).map(([path, page]) => ({ path, component: page.component, meta: { title: page.title, roles: page.roles } })),
      { path: '/profile', component: () => import('../views/ProfilePage.vue'), meta: { title: '个人信息' } },
      { path: '/password', component: () => import('../views/PasswordPage.vue'), meta: { title: '修改密码' } },
      { path: '/notifications', component: () => import('../views/NotificationsPage.vue'), meta: { title: '消息通知' } },
      { path: '/forbidden', component: () => import('../views/EmptyRoutePage.vue'), meta: { title: '无权访问' } },
      { path: '/:pathMatch(.*)*', component: () => import('../views/EmptyRoutePage.vue'), meta: { title: '页面不存在' } },
    ] },
  ],
  scrollBehavior: () => ({ top: 0 }),
})

router.beforeEach(async to => {
  // 电脑收银页只凭 capability token 访问，无论是否已登录都直接放行
  if (to.meta.cashier) return true
  if (to.meta.public) {
    if (localStorage.getItem(TOKEN_KEY)) {
      try { await loadSession(); return auth.permissions?.homePath || true } catch { return true }
    }
    return true
  }
  try {
    if (!await loadSession()) return { path: '/login', query: { redirect: to.fullPath } }
  } catch { return { path: '/login' } }
  if (!canRoleAccess(to.meta.roles, auth.user?.role)) return '/forbidden'
  return true
})
router.afterEach(to => { document.title = `${to.meta.title || '后台'} · 医院预约挂号管理` })
export default router
