// network-config.js 由本地启动脚本生成，随后端所在电脑的局域网地址更新。
const networkConfig = require('./network-config')

function getDevicePlatform() {
  try {
    if (typeof wx !== 'undefined' && typeof wx.getDeviceInfo === 'function') {
      const deviceInfo = wx.getDeviceInfo()
      if (deviceInfo && deviceInfo.platform) {
        return deviceInfo.platform
      }
    }
  } catch (e) {
    // 继续尝试开发者工具和旧基础库提供的接口。
  }

  try {
    if (typeof wx !== 'undefined' && typeof wx.getSystemInfoSync === 'function') {
      const systemInfo = wx.getSystemInfoSync()
      return (systemInfo && systemInfo.platform) || ''
    }
  } catch (e) {
    // Node 验证或无 wx 环境下按开发者工具处理。
  }

  return ''
}

const platform = getDevicePlatform()
const mobilePlatforms = ['android', 'ios', 'ohos']
const baseUrl = mobilePlatforms.indexOf(platform) !== -1
  ? networkConfig.deviceBaseUrl
  : networkConfig.devtoolsBaseUrl

module.exports = { baseUrl }
