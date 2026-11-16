// 不注册自定义回调，交由微信统一隐私授权弹窗处理，避免自定义弹窗没有
// agreePrivacyAuthorization 按钮时让原隐私接口一直处于 pending 状态。
function setupPrivacyAuthorization() {}

module.exports = { setupPrivacyAuthorization }
