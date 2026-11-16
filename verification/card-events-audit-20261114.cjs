// 同类卡片事件复检：加载真实组件、页面及 WXML，只模拟事件传播，不访问后端。
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const assert = require('node:assert/strict')

const root = path.resolve(__dirname, '../wechat-miniprogram/miniprogram')
const out = path.resolve(__dirname, '../.runtime/full-review-20261114-01')
const read = file => fs.readFileSync(path.join(root, file), 'utf8')
let component, page
const urls = []
const wx = { navigateTo: options => urls.push(options.url) }
// 页面依赖的桩：未登录态使 onShow 只清空列表，不触发后端请求
const pageStubs = {
  tab: { setTabBar() {} },
  auth: { isLoggedIn: () => false },
  api: {},
  adapter: {},
  toast: () => {}
}
vm.runInNewContext(read('components/order-card/order-card.js'), {
  require: () => ({}), Component: value => { component = value }
})
vm.runInNewContext(read('pages/order/list.js'), {
  require: spec => pageStubs[path.basename(spec)] || {}, wx, Page: value => { page = value }, console
})
// 用页面定义构造实例，使方法中的 this.data、this.setData 与生命周期可用
const instance = { data: { ...page.data, tabs: page.data.tabs }, setData(updates) { Object.assign(this.data, updates) } }
for (const [key, value] of Object.entries(page)) if (key !== 'data' && typeof value === 'function') instance[key] = value.bind(instance)
const tag = read('pages/order/list.wxml').match(/<order-card\b[\s\S]*?\/>/)[0]
const bindings = Object.fromEntries([...tag.matchAll(/\bbind:?([\w-]+)="(\w+)"/g)].map(m => [m[1], m[2]]))
const deliver = (name, detail) => { if (bindings[name]) instance[bindings[name]]({ detail }) }
const card = { data: { order: { id: 77 } }, triggerEvent: deliver }
assert.match(read('components/order-card/order-card.wxml'), /<view class="order-card" bindtap="onTap">/)
// 卡片正文点击：组件发出 select，页面导航一次且 id 有效。
component.methods.onTap.call(card)
const bodyUrls = [...urls]
assert.deepEqual(bodyUrls, ['/pages/order/detail?id=77'])
urls.length = 0
// 内部付款按钮使用 catchtap，按真实声明阻断原生 tap 的继续传播。
assert.match(read('components/order-card/order-card.wxml'), /data-action="pay"\s+catchtap="onAction"/)
component.methods.onAction.call(card, { currentTarget: { dataset: { action: 'pay' } } })
const lockedUrls = [...urls]
// 同一次导航未返回前再次点击，被页面导航锁拦截。
assert.deepEqual(lockedUrls, [])
// 详情页返回后 onShow 解锁，付款按钮再次导航一次。
instance.onShow()
urls.length = 0
component.methods.onAction.call(card, { currentTarget: { dataset: { action: 'pay' } } })
const actionUrls = [...urls]
assert.deepEqual(actionUrls, ['/pages/order/detail?id=77'])
const templates = []
function collect(dir) {
  for (const item of fs.readdirSync(dir, { withFileTypes: true })) {
    const file = path.join(dir, item.name)
    if (item.isDirectory()) collect(file)
    else if (item.name.endsWith('.wxml')) templates.push(file)
  }
}
collect(path.join(root, 'pages'))
const infoRowConsumers = templates.filter(file => /<info-row\b/.test(fs.readFileSync(file, 'utf8')))
const result = {
  source: '真实源码与WXML；原生/自定义事件传播为离线模拟，未作真机验收',
  orderBody: { singleNavigationWithValidId: bodyUrls.length === 1, navigateCount: bodyUrls.length, urls: bodyUrls },
  orderLockedDoubleTap: { navigateCountWhileLocked: lockedUrls.length },
  orderPayButtonAfterReturn: { singleNavigationWithValidId: actionUrls.length === 1, navigateCount: actionUrls.length, urls: actionUrls },
  infoRow: { templateConsumers: infoRowConsumers.map(file => path.relative(root, file)), activeBugConfirmed: false },
  businessCodeChanged: false, externalRequests: 0
}
fs.writeFileSync(path.join(out, 'card-events-results.json'), JSON.stringify(result, null, 2))
console.log(JSON.stringify(result, null, 2))