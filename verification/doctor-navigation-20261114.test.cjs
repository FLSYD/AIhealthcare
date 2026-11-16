// 医生卡片跳转专项测试（2026-11-14）。
// 加载真实的 doctor-card 组件、首页/科室页/排班详情页源码，并按各自 wxml 中的事件绑定模拟微信的事件派发；
// 只替换 wx、Page、Component、api 等边界，不访问后端。
// 运行：node --test verification/doctor-navigation-20261114.test.cjs
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const assert = require('node:assert/strict')
const { test } = require('node:test')

const miniRoot = path.resolve(__dirname, '..', 'wechat-miniprogram', 'miniprogram')
const read = (rel) => fs.readFileSync(path.join(miniRoot, rel), 'utf8')

// 万能桩：任意属性都是返回空数组 Promise 的函数，用于页面 require 的无关依赖
function anyStub() {
  const fn = () => undefined
  return new Proxy(fn, {
    get: (_t, key) => (key === 'then' ? undefined : (() => Promise.resolve([]))),
    apply: () => undefined
  })
}

function createWx() {
  const wx = {
    navs: [], toasts: [],
    navigateTo(option) { wx.navs.push(option.url) },
    showToast(option) { wx.toasts.push(option.title) },
    switchTab() {}, showLoading() {}, hideLoading() {}, hideToast() {},
    getStorageSync: () => '', setStorageSync() {}, removeStorageSync() {}
  }
  return wx
}

function runSource(rel, globals, requireMock) {
  const filename = path.join(miniRoot, rel)
  const sandbox = {
    console, Promise, Date, Math, JSON, Number, String, Array, Object, Error, RegExp,
    setTimeout, clearTimeout,
    require: requireMock || (() => anyStub()),
    module: { exports: {} },
    getApp: () => ({ globalData: {} }),
    getCurrentPages: () => [],
    ...globals
  }
  vm.runInNewContext(read(rel), sandbox, { filename })
}

function instantiate(definition) {
  const instance = {
    data: { ...(definition.data || {}) },
    setData(updates, callback) { Object.assign(this.data, updates); if (callback) callback() }
  }
  for (const [key, value] of Object.entries(definition)) {
    if (key !== 'data' && typeof value === 'function') instance[key] = value.bind(instance)
  }
  return instance
}

function loadPage(rel, wx) {
  let definition
  runSource(rel, { wx, Page: (value) => { definition = value } })
  return instantiate(definition)
}

// 解析页面 wxml 中 <doctor-card> 的事件绑定：{ 事件名: 页面方法名 }
function doctorCardBindings(pageWxml) {
  const tag = read(pageWxml).match(/<doctor-card[\s\S]*?\/>/)
  assert.ok(tag, `${pageWxml} 缺少 doctor-card`)
  const bindings = {}
  for (const match of tag[0].matchAll(/\bbind:?([\w-]+)="(\w+)"/g)) bindings[match[1]] = match[2]
  return bindings
}

// 解析组件根节点的原生 tap 绑定方法名
function componentRootTapMethod() {
  const match = read('components/doctor-card/doctor-card.wxml').match(/<view class="doctor-card"[^>]*\bbindtap="(\w+)"/)
  return match ? match[1] : null
}

function loadDoctorCard(doctor) {
  let definition
  runSource('components/doctor-card/doctor-card.js', { Component: (value) => { definition = value } })
  return { definition, doctor }
}

// 模拟一次真实点击：组件内部节点 tap → 组件根节点 bindtap → 自定义事件 → 原生 tap 冒泡到组件宿主节点
function tapDoctorCard(page, pageWxml, doctor) {
  const bindings = doctorCardBindings(pageWxml)
  const { definition } = loadDoctorCard(doctor)
  const deliveries = []
  const deliver = (eventName, detail) => {
    const method = bindings[eventName]
    if (!method) return
    deliveries.push({ eventName, detail })
    page[method]({ type: eventName, detail })
  }
  const component = {
    data: { ...definition.data, doctor, showDept: true },
    setData(updates) { Object.assign(this.data, updates) },
    triggerEvent: (name, detail) => deliver(name, detail)
  }
  const rootMethod = componentRootTapMethod()
  if (rootMethod) definition.methods[rootMethod].call(component, { type: 'tap', detail: { x: 10, y: 10 } })
  deliver('tap', { x: 10, y: 10 })
  return deliveries
}

function queryOf(url) {
  const query = {}
  for (const pair of url.split('?')[1].split('&')) {
    const [key, value] = pair.split('=')
    query[key] = value
  }
  return query
}

function openDetail(url) {
  const wx = createWx()
  const detailApi = {
    getDoctorById: () => Promise.resolve({ id: 7, name: '测试医生' }),
    getSchedules: () => Promise.resolve([])
  }
  let definition
  runSource('pages/schedule/detail.js', { wx, Page: (value) => { definition = value } }, (spec) => {
    if (spec.endsWith('/api')) return detailApi
    if (spec.endsWith('/adapter')) return { enrichDoctor: (d) => d, groupSchedules: () => ({ morning: [], afternoon: [], hasAnySchedule: false, hasAvailable: false }) }
    if (spec.endsWith('/format')) return { buildDateStrip: () => [{ value: '2026-11-15' }], formatFee: () => '¥20' }
    if (spec.endsWith('/toast')) return Object.assign((message) => wx.toasts.push(String(message)), { loading() {}, clear() {} })
    return anyStub()
  })
  const page = instantiate(definition)
  page.onLoad(queryOf(url))
  return { wx, page }
}

const doctor = { id: 7, name: '测试医生', title: '主治医师' }
const pages = [
  ['首页', 'pages/index/index'],
  ['科室页', 'pages/schedule/list']
]

for (const [label, base] of pages) {
  test(`${label}：单击医生卡片只跳转一次，且携带有效 doctorId`, () => {
    const wx = createWx()
    const page = loadPage(`${base}.js`, wx)
    const deliveries = tapDoctorCard(page, `${base}.wxml`, doctor)
    assert.equal(wx.navs.length, 1, `navigateTo 次数=${wx.navs.length}，事件派发=${JSON.stringify(deliveries)}，URL=${JSON.stringify(wx.navs)}`)
    assert.equal(queryOf(wx.navs[0]).doctorId, '7')
  })

  test(`${label}：跳转到的排班详情页不出现“参数错误”并能加载医生`, async () => {
    const wx = createWx()
    const page = loadPage(`${base}.js`, wx)
    tapDoctorCard(page, `${base}.wxml`, doctor)
    for (const url of wx.navs) {
      const detail = openDetail(url)
      await new Promise((resolve) => setImmediate(resolve))
      assert.deepEqual(detail.wx.toasts, [], `URL=${url}`)
      assert.equal(detail.page.data.doctor && detail.page.data.doctor.id, 7, `URL=${url}`)
    }
  })

  test(`${label}：快速连点两次只产生一次跳转，返回显示后可再次点击`, () => {
    const wx = createWx()
    const page = loadPage(`${base}.js`, wx)
    tapDoctorCard(page, `${base}.wxml`, doctor)
    tapDoctorCard(page, `${base}.wxml`, doctor)
    assert.equal(wx.navs.length, 1, `URL=${JSON.stringify(wx.navs)}`)
    page.onShow()
    tapDoctorCard(page, `${base}.wxml`, doctor)
    assert.equal(wx.navs.length, 2)
    assert.ok(wx.navs.every((url) => queryOf(url).doctorId === '7'))
  })

  test(`${label}：缺少医生 id 的事件不触发跳转`, () => {
    const wx = createWx()
    const page = loadPage(`${base}.js`, wx)
    page.onDoctorTap({ type: 'tap', detail: { x: 1, y: 2 } })
    page.onDoctorTap({ detail: {} })
    assert.equal(wx.navs.length, 0)
  })
}

test('排班详情页：doctorId 无效时仍提示参数错误（行为保持不变）', () => {
  const detail = openDetail('/pages/schedule/detail?doctorId=undefined')
  assert.deepEqual(detail.wx.toasts, ['参数错误'])
})
