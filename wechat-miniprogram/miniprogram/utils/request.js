const { baseUrl } = require('./config')

const TOKEN_KEY = 'token'

function getToken() {
  try {
    return wx.getStorageSync(TOKEN_KEY) || ''
  } catch (e) {
    return ''
  }
}

function request(options) {
  return new Promise((resolve, reject) => {
    const token = options.skipAuth ? '' : getToken()
    const sentToken = token
    wx.request({
      url: baseUrl + options.url,
      method: options.method || 'GET',
      data: options.data,
      header: {
        'Content-Type': 'application/json',
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
        ...(options.header || {})
      },
      success(res) {
        const body = res.data
        if (sentToken && sentToken !== getToken()) {
          const staleErr = new Error('请求在 token 更换后返回，已忽略')
          staleErr.code = 'STALE_TOKEN'
          reject(staleErr)
          return
        }
        if (res.statusCode === 401) {
          try {
            require('./auth').sessionExpired()
          } catch (e) {
            // ignore
          }
          const authErr = new Error((body && body.message) || '未登录或登录已过期')
          authErr.status = 401
          authErr.code = 'UNAUTHORIZED'
          reject(authErr)
          return
        }
        if (res.statusCode >= 500 && (!body || typeof body !== 'object' || body.code == null)) {
          const serverErr = new Error('服务处理失败，请稍后重试')
          serverErr.status = res.statusCode
          reject(serverErr)
          return
        }
        if (!body || body.code !== 200) {
          const bizErr = new Error((body && body.message) || '请求失败')
          bizErr.status = res.statusCode
          bizErr.code = body && body.code != null ? String(body.code) : undefined
          reject(bizErr)
          return
        }
        resolve(body.data)
      },
      fail(err) {
        const msg = err.errMsg || '网络错误'
        let netErr
        if (msg.indexOf('url not in domain list') !== -1) {
          netErr = new Error('域名未配置：开发阶段请勾选不校验合法域名')
          netErr.code = 'DOMAIN_NOT_ALLOWED'
        } else if (msg.indexOf('connect') !== -1 || msg.indexOf('timeout') !== -1 || msg.indexOf('fail') !== -1) {
          netErr = new Error('网络连接失败：请确认手机与电脑连接同一局域网，电脑端服务已启动，并允许防火墙访问服务端口后重试')
          netErr.code = 'NETWORK_ERROR'
        } else {
          netErr = new Error(msg)
        }
        reject(netErr)
      }
    })
  })
}

module.exports = { request, getToken }
