// 配药师前端专项测试（2026-11-14）
// 覆盖通知跳转、队列 query 解析、角色兜底及无效输入。
// 运行：node --test verification/pharmacist-client-20261114.test.cjs
const assert = require('node:assert/strict')
const { test, describe, before } = require('node:test')

const path = require('node:path')
const { pathToFileURL } = require('node:url')

// admin-web 为 type: module，纯函数文件直接以 ESM 动态导入，不依赖构建工具
let getNotificationTarget, parsePharmacyQuery, notificationTypeText
before(async () => {
  const file = path.resolve(__dirname, '..', 'admin-web', 'src', 'lib', 'notificationRoute.js')
  ;({ getNotificationTarget, parsePharmacyQuery, notificationTypeText } = await import(pathToFileURL(file).href))
})

describe('parsePharmacyQuery', () => {
  test('合法 id 和 status 返回解析值', () => {
    assert.deepEqual(parsePharmacyQuery({ id: '123', status: 'paid' }), { id: '123', status: 'paid', invalidId: false })
    assert.deepEqual(parsePharmacyQuery({ id: '7', status: 'dispensed' }), { id: '7', status: 'dispensed', invalidId: false })
  })

  test('id 只存在但非正整数返回 invalidId=true', () => {
    assert.deepEqual(parsePharmacyQuery({ id: '0' }), { id: null, status: null, invalidId: true })
    assert.deepEqual(parsePharmacyQuery({ id: '-1' }), { id: null, status: null, invalidId: true })
    assert.deepEqual(parsePharmacyQuery({ id: 'abc' }), { id: null, status: null, invalidId: true })
    assert.deepEqual(parsePharmacyQuery({ id: '' }), { id: null, status: null, invalidId: false })
  })

  test('status 非 paid/dispensed 返回 null', () => {
    assert.deepEqual(parsePharmacyQuery({ status: 'unpaid' }), { id: null, status: null, invalidId: false })
    assert.deepEqual(parsePharmacyQuery({ status: 'cancelled' }), { id: null, status: null, invalidId: false })
  })

  test('数组 query 取第一个值', () => {
    assert.deepEqual(parsePharmacyQuery({ id: ['5', '6'] }), { id: '5', status: null, invalidId: false })
    assert.deepEqual(parsePharmacyQuery({ status: ['paid', 'dispensed'] }), { id: null, status: 'paid', invalidId: false })
  })

  test('空 query 返回全 null', () => {
    assert.deepEqual(parsePharmacyQuery({}), { id: null, status: null, invalidId: false })
    assert.deepEqual(parsePharmacyQuery(null), { id: null, status: null, invalidId: false })
    assert.deepEqual(parsePharmacyQuery(undefined), { id: null, status: null, invalidId: false })
  })
})

describe('getNotificationTarget', () => {
  test('处方通知：配药师且有效 bizId 返回 /pharmacy?id=<bizId>', () => {
    const target = getNotificationTarget({ bizType: 'prescription', bizId: 42, type: 'prescription_paid' }, 'PHARMACIST')
    assert.deepEqual(target, { path: '/pharmacy', query: { id: '42' } })
  })

  test('处方通知：已发药类型额外带 status=dispensed', () => {
    const target = getNotificationTarget({ bizType: 'prescription', bizId: '7', type: 'prescription_dispensed' }, 'PHARMACIST')
    assert.deepEqual(target, { path: '/pharmacy', query: { id: '7', status: 'dispensed' } })
  })

  test('处方通知：非配药师返回 null', () => {
    assert.equal(getNotificationTarget({ bizType: 'prescription', bizId: 3 }, 'ADMIN'), null)
    assert.equal(getNotificationTarget({ bizType: 'prescription', bizId: 3 }, 'DOCTOR'), null)
  })

  test('处方通知：配药师但 bizId 无效返回 null', () => {
    assert.equal(getNotificationTarget({ bizType: 'prescription', bizId: 0 }, 'PHARMACIST'), null)
    assert.equal(getNotificationTarget({ bizType: 'prescription', bizId: '' }, 'PHARMACIST'), null)
    assert.equal(getNotificationTarget({ bizType: 'prescription' }, 'PHARMACIST'), null)
  })

  test('预约通知：配药师返回 /pharmacy', () => {
    const target = getNotificationTarget({ bizType: 'appointment', bizId: 10 }, 'PHARMACIST')
    assert.deepEqual(target, { path: '/pharmacy' })
  })

  test('预约通知：医生返回 /doctor/appointments?id=<bizId>', () => {
    const target = getNotificationTarget({ bizType: 'appointment', bizId: 20 }, 'DOCTOR')
    assert.deepEqual(target, { path: '/doctor/appointments', query: { id: '20' } })
  })

  test('预约通知：管理员返回 /appointment?id=<bizId>', () => {
    const target = getNotificationTarget({ bizType: 'appointment', bizId: 30 }, 'ADMIN')
    assert.deepEqual(target, { path: '/appointment', query: { id: '30' } })
  })

  test('预约通知：缺少 bizId 返回 null', () => {
    assert.equal(getNotificationTarget({ bizType: 'appointment' }, 'ADMIN'), null)
  })

  test('排班通知：医生返回 /doctor/schedules', () => {
    const target = getNotificationTarget({ bizType: 'schedule' }, 'DOCTOR')
    assert.deepEqual(target, { path: '/doctor/schedules' })
  })

  test('排班通知：管理员返回 /schedule', () => {
    const target = getNotificationTarget({ bizType: 'schedule' }, 'ADMIN')
    assert.deepEqual(target, { path: '/schedule' })
  })

  test('排班通知：配药师返回 /pharmacy', () => {
    const target = getNotificationTarget({ bizType: 'schedule' }, 'PHARMACIST')
    assert.deepEqual(target, { path: '/pharmacy' })
  })

  test('未知 bizType 返回 null', () => {
    assert.equal(getNotificationTarget({ bizType: 'unknown' }, 'ADMIN'), null)
    assert.equal(getNotificationTarget({}, 'ADMIN'), null)
  })
})

describe('notificationTypeText', () => {
  test('优先使用 item.typeText', () => {
    assert.equal(notificationTypeText({ typeText: '用户自定义标题' }), '用户自定义标题')
    assert.equal(notificationTypeText({ typeText: '用户自定义标题', type: 'prescription_paid' }), '用户自定义标题')
  })

  test('typeText 不存在时按 type 查表', () => {
    assert.equal(notificationTypeText({ type: 'prescription_paid' }), '处方已缴费')
    assert.equal(notificationTypeText({ type: 'prescription_dispensed' }), '处方已发药')
  })

  test('type 未在表中返回空', () => {
    assert.equal(notificationTypeText({ type: 'unknown' }), '')
    assert.equal(notificationTypeText({}), '')
  })
})
