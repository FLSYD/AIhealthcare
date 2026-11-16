// 挂号项目使用北京时间，避免浏览器采用其他时区时跨日筛选错误。
export function hospitalToday(now = new Date()) {
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit',
  }).formatToParts(now)
  const values = Object.fromEntries(parts.map(part => [part.type, part.value]))
  return `${values.year}-${values.month}-${values.day}`
}

// 通过现有接口先筛选再分页，保留真实总数和每页条数；当天所有时段均保留。
export function scheduleQuery(params, { includeHistory = false, date = '' } = {}, now = new Date()) {
  const today = hospitalToday(now)
  const selectedDate = date && (includeHistory || date >= today) ? date : ''
  return {
    ...params,
    startDate: selectedDate || (includeHistory ? undefined : today),
    endDate: selectedDate || undefined,
  }
}
