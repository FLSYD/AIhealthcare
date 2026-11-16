<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'

// 微信 Native 电脑收银页：仅凭 capability token 访问，不依赖登录态，不展示患者信息
const POLL_INTERVAL = 3000
const route = useRoute()
const token = computed(() => String(route.params.token || ''))
const apiBase = computed(() => `/api/payments/wechat/native/cashier/${encodeURIComponent(token.value)}`)

const view = ref(null)
const loadError = ref('')
const loading = ref(true)
const netHint = ref('')
const qrNonce = ref(0)
const qrFailed = ref(false)

let timer = null
let polling = false
let disposed = false

const TERMINAL = ['SUCCEEDED', 'CLOSED', 'FAILED', 'NEEDS_REVIEW']
const STATUS_TEXT = {
  CREATING: { title: '正在生成支付单', desc: '请稍候，二维码生成后会自动显示。', tone: 'pending' },
  PENDING: { title: '等待扫码支付', desc: '请使用手机微信“扫一扫”扫描上方二维码，并在手机上确认支付。', tone: 'pending' },
  UNKNOWN: { title: '支付结果确认中', desc: '正在向微信确认支付结果，请勿重复支付。', tone: 'warning' },
  CLOSING: { title: '正在关闭支付单', desc: '关闭结果确认前请勿重复支付。', tone: 'warning' },
  SUCCEEDED: { title: '支付成功', desc: '已收到付款，可以关闭此页面。', tone: 'success' },
  CLOSED: { title: '支付已关闭', desc: '该支付单已关闭，二维码不再有效。如需支付请回到手机重新发起。', tone: 'muted' },
  FAILED: { title: '支付失败', desc: '本次支付未成功，请回到手机重新发起。', tone: 'danger' },
  NEEDS_REVIEW: { title: '已支付待人工处理，请勿重复支付', desc: '请联系医院工作人员核对，请勿再次付款。', tone: 'warning' },
}

const status = computed(() => view.value?.status || '')
const statusInfo = computed(() => STATUS_TEXT[status.value] || STATUS_TEXT.UNKNOWN)
const terminal = computed(() => TERMINAL.includes(status.value))
const showQr = computed(() => status.value === 'PENDING')
const amountText = computed(() => (view.value?.amount != null ? String(view.value.amount) : ''))
const qrSrc = computed(() => `${apiBase.value}/qr${qrNonce.value ? `?t=${qrNonce.value}` : ''}`)
const expireText = computed(() => {
  const value = Number(view.value?.expiresAt)
  if (!Number.isFinite(value) || value <= 0) return ''
  return new Date(value).toLocaleTimeString('zh-CN', { hour12: false })
})

// 兼容统一 Result 包装与直接返回 view 两种响应
function unwrap(body) {
  if (body && typeof body === 'object' && 'code' in body && 'data' in body) {
    if (body.code !== 200 && body.code !== 0) throw new Error(body.message || body.msg || '查询失败')
    return body.data
  }
  return body
}

async function fetchView() {
  const response = await fetch(apiBase.value, { headers: { Accept: 'application/json' }, cache: 'no-store' })
  if (response.status === 403 || response.status === 404) throw Object.assign(new Error('收银页链接无效或已过期，请回到手机重新发起支付。'), { fatal: true })
  if (!response.ok) throw new Error(`请求失败（${response.status}）`)
  const data = unwrap(await response.json())
  if (!data || typeof data.status !== 'string') throw new Error('支付单响应异常')
  return data
}

function stop() {
  if (timer) clearTimeout(timer)
  timer = null
}

function schedule() {
  if (disposed || timer || terminal.value) return
  timer = setTimeout(() => {
    timer = null
    refresh()
  }, POLL_INTERVAL)
}

async function refresh() {
  if (disposed || polling) return
  polling = true
  try {
    const next = await fetchView()
    if (disposed) return
    view.value = next
    loadError.value = ''
    netHint.value = ''
  } catch (error) {
    if (disposed) return
    if (error.fatal) {
      view.value = null
      loadError.value = error.message
      loading.value = false
      return
    }
    if (view.value) netHint.value = '网络不稳定，正在重试'
    else loadError.value = error.message || '加载失败'
  } finally {
    polling = false
    loading.value = false
  }
  if (!disposed && !terminal.value && !(loadError.value && !view.value)) schedule()
}

function retry() {
  loadError.value = ''
  loading.value = true
  stop()
  refresh()
}

function reloadQr() {
  qrFailed.value = false
  qrNonce.value = Date.now()
}

onMounted(() => {
  document.title = '微信扫码支付 · 医院预约挂号'
  refresh()
})

onBeforeUnmount(() => {
  disposed = true
  stop()
})
</script>

<template>
  <main class="cashier">
    <section class="cashier__card">
      <header class="cashier__head">
        <span class="cashier__tag">微信真实支付</span>
        <h1>微信扫码支付</h1>
      </header>

      <div v-if="loading" class="cashier__state">正在加载支付信息…</div>

      <div v-else-if="loadError && !view" class="cashier__state cashier__state--danger">
        <p>{{ loadError }}</p>
        <button v-if="!loadError.includes('无效')" type="button" class="cashier__btn" @click="retry">重新加载</button>
      </div>

      <template v-else-if="view">
        <div class="cashier__amount">
          <span class="cashier__amount-label">应付金额</span>
          <strong>¥{{ amountText }}</strong>
          <span class="cashier__warn">此为真实支付，扫码确认后将真实扣款，请核对金额。</span>
        </div>

        <div v-if="showQr" class="cashier__qr">
          <img v-if="!qrFailed" :src="qrSrc" alt="微信支付二维码" width="240" height="240" @error="qrFailed = true" />
          <div v-else class="cashier__qr-failed">
            <span>二维码加载失败</span>
            <button type="button" class="cashier__btn" @click="reloadQr">重新加载二维码</button>
          </div>
          <p v-if="expireText" class="cashier__expire">二维码有效至 {{ expireText }}</p>
        </div>

        <ol v-if="showQr" class="cashier__steps">
          <li>打开手机微信，点击“扫一扫”。</li>
          <li>扫描上方二维码，核对金额后在手机上确认支付。</li>
          <li>支付完成后本页面会自动更新，无需刷新。</li>
        </ol>

        <div :class="['cashier__status', `cashier__status--${statusInfo.tone}`]">
          <strong>{{ statusInfo.title }}</strong>
          <p>{{ statusInfo.desc }}</p>
          <p v-if="view.message && status !== 'NEEDS_REVIEW'" class="cashier__msg">{{ view.message }}</p>
          <p v-if="netHint" class="cashier__msg">{{ netHint }}</p>
        </div>
      </template>
    </section>
  </main>
</template>

<style scoped>
.cashier {
  min-height: 100vh;
  display: flex;
  align-items: flex-start;
  justify-content: center;
  padding: 48px 16px;
  box-sizing: border-box;
  background: #f5f7fa;
  color: #333;
}
.cashier__card {
  width: 100%;
  max-width: 420px;
  padding: 28px 24px;
  box-sizing: border-box;
  background: #fff;
  border-radius: 12px;
  box-shadow: 0 4px 24px rgba(0, 139, 132, 0.08);
  text-align: center;
}
.cashier__head h1 {
  margin: 12px 0 0;
  font-size: 20px;
}
.cashier__tag {
  display: inline-block;
  padding: 2px 12px;
  border-radius: 12px;
  font-size: 12px;
  color: #fff;
  background: linear-gradient(135deg, #4ade80, #16a34a);
}
.cashier__amount {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin: 20px 0 12px;
}
.cashier__amount-label {
  font-size: 13px;
  color: #666693;
}
.cashier__amount strong {
  font-size: 34px;
  color: #00b894;
}
.cashier__warn {
  font-size: 12px;
  color: #d97706;
}
.cashier__qr {
  margin: 12px 0;
}
.cashier__qr img {
  display: block;
  margin: 0 auto;
  width: 240px;
  height: 240px;
}
.cashier__qr-failed {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  width: 240px;
  height: 240px;
  margin: 0 auto;
  font-size: 14px;
  color: #666693;
}
.cashier__expire {
  margin: 8px 0 0;
  font-size: 12px;
  color: #666693;
}
.cashier__steps {
  margin: 12px 0;
  padding-left: 20px;
  text-align: left;
  font-size: 13px;
  line-height: 1.8;
  color: #666693;
}
.cashier__status {
  margin-top: 16px;
  padding: 12px;
  border-radius: 8px;
  background: #f5f7fa;
}
.cashier__status p {
  margin: 6px 0 0;
  font-size: 13px;
  line-height: 1.6;
  color: #666693;
}
.cashier__status .cashier__msg {
  font-size: 12px;
  color: #999;
}
.cashier__status--success strong { color: #22c55e; }
.cashier__status--warning strong { color: #d97706; }
.cashier__status--danger strong { color: #ff4d4f; }
.cashier__state {
  padding: 40px 0;
  font-size: 14px;
  color: #666693;
}
.cashier__state--danger p {
  color: #ff4d4f;
}
.cashier__btn {
  margin-top: 8px;
  padding: 6px 18px;
  border: 1px solid #00b894;
  border-radius: 16px;
  font-size: 13px;
  color: #00b894;
  background: #fff;
  cursor: pointer;
}
</style>
