const auth = require('./utils/auth')
const { setupPrivacyAuthorization } = require('./utils/privacy')

App({
  globalData: {
    token: '',
    user: null,
    triageSymptom: '',
    triageResult: null,
    pendingDeptId: 0,
    pendingKeyword: ''
  },

  onLaunch() {
    setupPrivacyAuthorization()

    const token = auth.getToken()
    const user = auth.getUser()
    if (token) {
      this.globalData.token = token
      this.globalData.user = user
      auth.fetchMe().catch((err) => {
        // 只有后端明确返回 401 才清除登录态；网络错误和 5xx 保留 token，下次请求再重试
        if (err && err.status === 401) auth.sessionExpired()
      })
    } else if (auth.shouldAutoLogin()) {
      auth.wxLogin().catch(() => {})
    }
  }
})
