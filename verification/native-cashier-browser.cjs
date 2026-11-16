const { chromium } = require('../.runtime/browser-runtime/node_modules/playwright')
const fs = require('node:fs')
const path = require('node:path')
const assert = require('node:assert/strict')

// 页面验收使用不可支付的合成二维码和受控接口响应，不创建真实支付或点击付款。
async function main() {
  const evidence = path.resolve(__dirname, '../.runtime/native-payment')
  const localChrome = path.join(process.env.LOCALAPPDATA || 'C:/Users/HP/AppData/Local', 'ms-playwright/chromium-1234/chrome-win64/chrome.exe')
  const browser = process.env.NATIVE_BROWSER_CDP
    ? await chromium.connectOverCDP(process.env.NATIVE_BROWSER_CDP)
    : await chromium.launch({ headless: true, executablePath: fs.existsSync(localChrome) ? localChrome : undefined })
  const page = await browser.newPage({ viewport: { width: 1100, height: 1000 } })
  const consoleErrors = []
  page.on('pageerror', error => consoleErrors.push(error.message))
  let status = 'PENDING'
  let calls = 0
  let denyToken = false
  const token = 'synthetic-browser-preview-not-payable'
  const fixture = {
    paymentId: 900001, businessType: 'RECHARGE', amount: '0.01', amountFen: 1,
    expiresAt: Date.now() + 300000,
    codeImageUrl: `/api/payments/wechat/native/cashier/${token}/qr`,
    cashierUrl: `http://127.0.0.1:15173/cashier/${token}`,
    message: '页面验收数据，不可用于付款'
  }
  await page.route(`**/api/payments/wechat/native/cashier/${token}**`, async route => {
    if (route.request().url().includes('/qr')) {
      return route.fulfill({ contentType: 'image/png', body: fs.readFileSync(path.join(evidence, 'synthetic-qr.png')) })
    }
    calls += 1
    if (denyToken) return route.fulfill({ status: 403, contentType: 'application/json', body: '{}' })
    return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, data: { ...fixture, status } }) })
  })
  try {
    await page.goto(`http://127.0.0.1:15173/cashier/${token}`)
    await page.getByText('等待扫码支付', { exact: true }).waitFor()
    await page.getByRole('img', { name: '微信支付二维码' }).waitFor()
    await page.locator('img').evaluate(image => image.decode())
    await page.screenshot({ path: path.join(evidence, 'cashier-pending.png'), fullPage: true })
    assert.match(await page.locator('main').innerText(), /真实扣款/)
    assert.match(await page.locator('main').innerText(), /0\.01/)
    status = 'SUCCEEDED'
    await page.getByText('支付成功', { exact: true }).waitFor({ timeout: 8000 })
    assert.equal(await page.getByRole('img', { name: '微信支付二维码' }).count(), 0)
    await page.screenshot({ path: path.join(evidence, 'cashier-succeeded.png'), fullPage: true })
    const terminalCalls = calls
    await page.waitForTimeout(3300)
    assert.equal(calls, terminalCalls, '成功终态必须停止轮询')
    status = 'NEEDS_REVIEW'
    await page.reload()
    await page.getByText('已支付待人工处理，请勿重复支付', { exact: true }).waitFor()
    await page.screenshot({ path: path.join(evidence, 'cashier-review.png'), fullPage: true })
    assert.equal(await page.getByRole('img', { name: '微信支付二维码' }).count(), 0)
    await page.setViewportSize({ width: 390, height: 844 })
    status = 'PENDING'
    await page.reload()
    await page.getByText('等待扫码支付', { exact: true }).waitFor()
    await page.screenshot({ path: path.join(evidence, 'cashier-mobile.png'), fullPage: true })
    assert.ok(await page.locator('body').evaluate(el => el.scrollWidth <= window.innerWidth))
    denyToken = true
    await page.getByText('收银页链接无效或已过期，请回到手机重新发起支付。', { exact: true }).waitFor({ timeout: 8000 })
    assert.equal(await page.getByRole('img', { name: '微信支付二维码' }).count(), 0)
    await page.route('**/api/payments/wechat/native/cashier/expired-token', route => route.fulfill({ status: 403, contentType: 'application/json', body: '{}' }))
    await page.goto('http://127.0.0.1:15173/cashier/expired-token')
    await page.getByText('收银页链接无效或已过期，请回到手机重新发起支付。', { exact: true }).waitFor()
    assert.equal(await page.getByRole('button', { name: '重新加载' }).count(), 0)
    assert.deepEqual(consoleErrors, [])
    fs.writeFileSync(path.join(evidence, 'browser-results.json'), JSON.stringify({
      source: 'actual Vue cashier with mocked API and nonpayable QR',
      actualPaymentCreated: false, realMoneyCharged: false,
      pendingQrRendered: true, realPaymentNoticeVisible: true,
      successHidesQr: true, terminalPollingStopped: true, reviewHidesQr: true,
      narrowViewportFits: true, pageErrors: consoleErrors,
      forbiddenTokenShowsExpiredMessage: true
      ,loadedTokenExpiryHidesOldQr: true
    }, null, 2))
    console.log('Native cashier browser checks passed; synthetic data only, no payment.')
  } finally { await browser.close() }
}
main().catch(error => { console.error(error); process.exitCode = 1 })
