const { enrichDepartments } = require('../../utils/dept-meta')
const { goDeptTab } = require('../../utils/tab')
const api = require('../../utils/api')

// 参数含非法百分号时保留原文，避免 URIError 使页面一直停在加载中
function safeDecode(value) {
  try {
    return decodeURIComponent(value)
  } catch (e) {
    return String(value)
  }
}

Page({
  data: {
    loading: true,
    symptom: '',
    summary: '',
    symptomAnalysis: '',
    commonSigns: [],
    preliminaryConclusion: '',
    advice: '',
    urgent: false,
    source: '',
    sourceLabel: '',
    departments: []
  },

  onLoad(options) {
    const app = getApp()
    const cached = app.globalData.triageResult
    if (cached && cached.departments) {
      app.globalData.triageResult = null
      this.applyResult(cached)
      return
    }

    const symptom = safeDecode(options.symptom || '')
    if (symptom) {
      this.setData({ symptom })
      this.fetchAnalyze(symptom)
      return
    }

    this.setData({ loading: false })
    wx.showToast({ title: '请先描述症状', icon: 'none' })
    setTimeout(() => wx.navigateBack(), 1500)
  },

  fetchAnalyze(symptom) {
    this.setData({ loading: true })
    api.triageAnalyze(symptom)
      .then((result) => this.applyResult({ ...result, symptom }))
      .catch((err) => {
        wx.showToast({ title: err.message || '分析失败', icon: 'none' })
        setTimeout(() => wx.navigateBack(), 1500)
      })
      .finally(() => this.setData({ loading: false }))
  },

  applyResult(result) {
    const source = result.source || 'fallback'
    this.setData({
      loading: false,
      symptom: result.symptom || '',
      summary: result.summary || '',
      symptomAnalysis: result.symptomAnalysis || result.summary || '',
      commonSigns: result.commonSigns || [],
      preliminaryConclusion: result.preliminaryConclusion || '',
      advice: result.advice || '',
      urgent: !!result.urgent,
      source,
      sourceLabel: source === 'deepseek' ? 'AI 分析' : '智能推荐',
      departments: enrichDepartments(result.departments || [])
    })
  },

  onDeptSelect(e) {
    goDeptTab({ deptId: e.detail.id })
  },

  onViewAll() {
    const first = this.data.departments[0]
    if (first) {
      goDeptTab({ deptId: first.id })
    }
  }
})
