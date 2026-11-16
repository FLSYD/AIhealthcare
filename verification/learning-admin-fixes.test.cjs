// 管理页优先问题回归：执行真实 SFC script，只替换生命周期、路由和 API 边界。
// 运行：node --test verification/learning-admin-fixes.test.cjs
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const assert = require('node:assert/strict')
const { test } = require('node:test')

const sourceRoot = path.resolve(__dirname, '../admin-web/src')
const ref = value => ({ value })
const reactive = value => ({ ...value })
const computed = getter => ({ get value() { return getter() } })
const stripImports = source => source.replace(/^import .*$/gm, '')

function readSfcScript(file) {
  const source = fs.readFileSync(path.join(sourceRoot, file), 'utf8')
  const match = source.match(/<script setup>([\s\S]*?)<\/script>/)
  assert.ok(match, `${file} 缺少 script setup`)
  return match[1]
}

function evaluateSfc(file, globals, names) {
  const module = { exports: {} }
  const context = {
    module,
    console,
    Date,
    Intl,
    Array,
    Object,
    Number,
    String,
    Math,
    Set,
    Map,
    Promise,
    Error,
    RegExp,
    ref,
    reactive,
    computed,
    ...globals,
  }
  vm.runInNewContext(`${stripImports(readSfcScript(file))}\nmodule.exports={${names.join(',')}}`, context)
  return module.exports
}

function loadAppointments(latest = { id: 7, status: 'cancelled' }) {
  const watches = []
  const gets = []
  const puts = []
  const messages = []
  const loads = []
  const pageError = ref('')
  const page = evaluateSfc('views/admin/AppointmentsPage.vue', {
    watch: (source, callback) => watches.push({ source, callback }),
    onMounted() {},
    onBeforeUnmount() {},
    onBeforeRouteLeave() {},
    onBeforeRouteUpdate() {},
    useRoute: () => ({ query: {} }),
    useAppointmentList: () => ({
      rows: ref([]), total: ref(0), page: ref(1), size: ref(10),
      filters: reactive({ keyword: '', status: '', scheduleDate: '' }),
      loading: ref(false), error: pageError,
      load: async options => loads.push(options), search: async () => {}, reset: async () => {},
    }),
    useStatusRefresh() {},
    registerNavigationGuard: () => () => {},
    api: {
      async get(url) { gets.push(url); return latest },
      async put(url) { puts.push(url); return {} },
    },
    confirmAction: async () => true,
    TOKEN_KEY: 'admin-token',
    appointmentOptions: [],
    appointmentIssue: row => row?.issue || '',
    money: () => '',
    dateTime: () => '',
    visitTime: () => '',
    ElMessage: {
      warning(message) { messages.push(message) },
      success(message) { messages.push(message) },
    },
    localStorage: { getItem: () => null },
  }, [
    'visible', 'detail', 'detailLoading', 'detailError', 'detailRowId', 'busy', 'error',
    'validRow', 'canCancel', 'action', 'allowLeave',
  ])
  return { page, watches, gets, puts, messages, loads }
}

test('预约详情失败只保守锁定同一预约，关闭抽屉后清空瞬时状态', () => {
  const { page, watches } = loadAppointments()
  const failedRow = { id: 11, status: 'pending', prescriptionStatus: null }
  const otherRow = { id: 12, status: 'pending', prescriptionStatus: null }

  page.visible.value = true
  page.detailRowId.value = 11
  page.detailError.value = '详情加载失败'
  assert.equal(page.validRow(failedRow), false)
  assert.equal(page.canCancel(failedRow), false)
  assert.equal(page.validRow(otherRow), true)
  assert.equal(page.canCancel(otherRow), true)

  page.detail.value = { id: 11 }
  page.detailLoading.value = true
  page.visible.value = false
  watches[1].callback(false)
  assert.equal(page.detailError.value, '')
  assert.equal(page.detailLoading.value, false)
  assert.equal(page.detail.value, null)
  assert.equal(page.detailRowId.value, null)
})

test('预约动作在确认后重读 latest，状态变化时不提交写请求', async () => {
  const { page, gets, puts, messages, loads } = loadAppointments()
  await page.action({ id: 7, status: 'pending', prescriptionStatus: null }, 'cancel')
  assert.deepEqual(gets, ['/appointment/7'])
  assert.deepEqual(puts, [])
  assert.equal(loads.length, 1)
  assert.match(messages[0], /状态已变化/)
})

test('预约 busy 状态阻止动作并守住已登录页面导航，登录跳转保持原行为', async () => {
  const { page, gets } = loadAppointments()
  page.busy.value = true
  assert.equal(page.allowLeave({ path: '/menu' }), false)
  assert.equal(page.allowLeave({ path: '/login' }), true)
  await page.action({ id: 7, status: 'pending', prescriptionStatus: null }, 'cancel')
  assert.deepEqual(gets, [])
})

function loadSchedules() {
  return evaluateSfc('views/admin/SchedulesPage.vue', {
    nextTick: callback => Promise.resolve().then(callback),
    onMounted() {},
    useStatusRefresh() {},
    usePagedList: () => ({
      filters: reactive({ doctorId: null }), rows: ref([]), total: ref(0), page: ref(1), size: ref(10),
      loading: ref(false), error: ref(''), load: async () => {}, search: async () => {}, reset: async () => {},
    }),
    api: { async get() { return { list: [], total: 0 } } },
    loadAll: async () => [],
    confirmAction: async () => true,
    runAction: async task => ({ ok: true, data: await task() }),
    dateTime: () => '',
    hospitalToday: () => '2026-11-14',
  }, [
    'form', 'rules', 'batchRules', 'identityLocked', 'openEdit', 'resetForm',
    'singleDateDisabled', 'isPastDate', 'totalHistory',
  ])
}

function validateRule(rule, value) {
  return new Promise(resolve => rule.validator({}, value, error => resolve(error || null)))
}

test('历史预约身份锁使用包含取消历史的 appointmentCount，并兼容旧响应回退 bookedCount', async () => {
  const page = loadSchedules()
  page.openEdit({ id: 1, scheduleDate: '2026-11-01', bookedCount: 0, appointmentCount: 2 })
  assert.equal(page.identityLocked.value, true)
  assert.equal(page.totalHistory(page.form), 2)

  page.openEdit({ id: 2, scheduleDate: '2026-11-02', bookedCount: 3 })
  assert.equal(page.identityLocked.value, true)
  assert.equal(page.totalHistory(page.form), 3)

  page.openEdit({ id: 3, scheduleDate: '2026-11-03', bookedCount: 0, appointmentCount: 0 })
  assert.equal(page.identityLocked.value, false)
})

test('单条排班允许历史原日期，新日期和其他过去日期被表单规则拒绝', async () => {
  const page = loadSchedules()
  const dateRule = page.rules.scheduleDate[1]

  page.openEdit({ id: 8, scheduleDate: '2026-11-01', bookedCount: 0, appointmentCount: 0 })
  assert.equal(await validateRule(dateRule, '2026-11-01'), null)
  assert.equal(page.singleDateDisabled(new Date(2026, 10, 1)), false)
  assert.match((await validateRule(dateRule, '2026-11-02')).message, /不能早于今天/)

  page.resetForm()
  assert.match((await validateRule(dateRule, '2026-11-13')).message, /不能早于今天/)
  assert.equal(page.singleDateDisabled(new Date(2026, 10, 13)), true)
  assert.equal(await validateRule(dateRule, '2026-11-14'), null)
  assert.equal(page.singleDateDisabled(new Date(2026, 10, 14)), false)
})

test('单条与批量时段均按后端 HH:mm、上午、下午严格格式校验', async () => {
  const page = loadSchedules()
  const singleRule = page.rules.timeSlot[0]
  const batchRule = page.batchRules.timeSlots[0]

  for (const value of ['上午', '下午', '00:00', '08:00', '23:59']) {
    assert.equal(await validateRule(singleRule, value), null, `单条合法时段 ${value}`)
  }
  for (const value of ['', '8:00', '24:00', '09:60', '09:0', '即使已过也应交给后端']) {
    assert.ok(await validateRule(singleRule, value), `单条非法时段 ${value}`)
  }

  assert.equal(await validateRule(batchRule, ['上午', '09:00', '23:59']), null)
  for (const values of [['8:00'], ['24:00'], ['09:60'], ['09:00', '即使已过也应交给后端']]) {
    const error = await validateRule(batchRule, values)
    assert.ok(error, `批量非法时段 ${values.join(',')}`)
    assert.match(error.message, /时段必须为 HH:mm、上午或下午/)
  }
})

function loadMenus() {
  return evaluateSfc('views/admin/MenusPage.vue', {
    nextTick: callback => Promise.resolve().then(callback),
    onMounted() {},
    onBeforeUnmount() {},
    api: { async get() { return [] } },
    confirmAction: async () => true,
    roleLabel: role => role,
    pageRegistry: {
      '/dashboard': { title: '数据概览', roles: ['ADMIN'], icon: 'DataAnalysis' },
      '/doctor/overview': { title: '工作概览', roles: ['DOCTOR'], icon: 'House' },
      '/pharmacy': { title: '配药工作台', roles: ['PHARMACIST'], icon: 'FirstAidKit' },
      '/medicine': { title: '药品管理', roles: ['ADMIN', 'PHARMACIST'], icon: 'FirstAidKit' },
    },
    refreshMenus: async () => {},
    ElMessage: { success() {} },
  }, ['defaultPaths', 'isDefault', 'open', 'form', 'pageRoles'])
}

test('药师工作台纳入默认首页保护，编辑时锁定合法角色但保留菜单名称', async () => {
  const page = loadMenus()
  assert.ok(page.defaultPaths.includes('/pharmacy'))
  assert.equal(page.isDefault({ path: '/pharmacy' }), true)
  assert.equal(page.isDefault({ path: '/medicine' }), false)

  await page.open({ id: 9, name: '药师入口', path: '/pharmacy', roles: 'ADMIN', icon: 'FirstAidKit', sortOrder: 2 })
  assert.equal(page.form.path, '/pharmacy')
  assert.deepEqual([...page.form.roles], ['PHARMACIST'])
  assert.equal(page.form.name, '药师入口')
  assert.deepEqual([...page.pageRoles('/pharmacy')], ['PHARMACIST'])
})
