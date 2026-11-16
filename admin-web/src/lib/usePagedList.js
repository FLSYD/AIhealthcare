import { onBeforeUnmount, reactive, ref } from 'vue'
import { api } from './api'

export function usePagedList(endpoint, initialFilters = {}, options = {}) {
  const filters = reactive({ ...initialFilters })
  const applied = { ...initialFilters }
  const rows = ref([]), total = ref(0), page = ref(1), size = ref(10), loading = ref(false), error = ref('')
  let sequence = 0
  function commit() {
    for (const key of Object.keys(applied)) delete applied[key]
    Object.assign(applied, filters)
  }
  async function load(loadOptions = {}) {
    const background = loadOptions?.background === true
    if (background && loading.value) return
    const current = ++sequence
    if (!background) { loading.value = true; error.value = '' }
    try {
      const path = typeof endpoint === 'function' ? endpoint() : endpoint
      const params = { ...applied, pageNum: page.value, pageSize: size.value }
      const requestOptions = { quiet: background }
      const data = options.fetchPage
        ? await options.fetchPage(params, requestOptions)
        : await api.get(path, { ...requestOptions, params })
      if (current !== sequence) return
      error.value = ''
      rows.value = data?.list || []
      total.value = Number(data?.total || 0)
      if (page.value > 1 && !rows.value.length) {
        const lastPage = Math.max(1, Math.ceil(total.value / size.value))
        if (lastPage !== page.value) {
          page.value = lastPage
          if (total.value) return load(loadOptions)
        }
      }
    } catch (failure) {
      if (current === sequence) {
        if (!background) { rows.value = []; total.value = 0 }
        error.value = background ? `状态同步失败，当前显示上次结果：${failure.message}` : failure.message
      }
    } finally { if (current === sequence) loading.value = false }
  }
  async function search() { commit(); page.value = 1; await load() }
  async function reset() { for (const key of Object.keys(filters)) delete filters[key]; Object.assign(filters, initialFilters); await search() }
  onBeforeUnmount(() => sequence++)
  return { filters, applied, rows, total, page, size, loading, error, load, search, reset, commit }
}
