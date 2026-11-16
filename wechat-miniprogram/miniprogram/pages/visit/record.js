const api = require('../../utils/api')
const toast = require('../../utils/toast')

Page({
  data: {
    items: [],
    loading: true,
    empty: false,
    loadError: false
  },

  onLoad(options) {
    const id = options.id
    if (!id) {
      toast('参数错误')
      this.setData({ loading: false, empty: true })
      return
    }
    api.getConsultation(id)
      .then((record) => {
        if (!record) {
          this.setData({ items: [], loading: false, empty: true, loadError: false })
          return
        }
        const items = [
          { label: '主诉', value: record.chiefComplaint || '-' },
          { label: '现病史', value: record.presentIllness || '-' },
          { label: '诊断', value: record.diagnosis || '-' },
          { label: '治疗方案', value: record.treatmentPlan || '-' },
          { label: '医嘱', value: record.doctorAdvice || '-' }
        ]
        this.setData({ items, loading: false, empty: false, loadError: false })
      })
      .catch((err) => {
        this.setData({ loading: false, loadError: true })
        toast(err.message || '加载失败')
      })
  }
})
