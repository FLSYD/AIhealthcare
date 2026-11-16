# AIhealthcare 微信小程序患者端

患者端通过 `utils/api.js` 调用后端接口，少量科室展示组件使用 `mock/` 中的静态元数据。源码位于仓库的 `wechat-miniprogram/miniprogram/`。

## 页面结构

| Tab/页面 | 路径 |
| --- | --- |
| 首页 | `pages/index/index` |
| AI 导诊 | `pages/triage/index` → `pages/triage/result` |
| 订单 | `pages/order/list` → `detail` / `evaluate` |
| 我的 | `pages/mine/mine` |
| 排班查询 | `pages/schedule/list` → `detail` |
| 预约挂号 | `pages/appointment/confirm` |

## 安装与导入

从仓库根目录执行：

```powershell
Set-Location .\wechat-miniprogram\miniprogram
npm ci
npm run build:npm
```

`npm run build:npm` 会生成或更新 `miniprogram_npm`。之后用微信开发者工具导入仓库内的 `wechat-miniprogram/`，工具的 `miniprogramRoot` 会进入内层 `miniprogram/` 源码目录。

公开使用前，请将以下两份配置中的 AppID 换成使用者本人的 AppID，并确认两份值一致：

- `wechat-miniprogram/project.config.json`
- `wechat-miniprogram/miniprogram/project.config.json`

AppID 不是 AppSecret。真实 AppSecret 只能保存在本机 `.local/wechat.properties`，不得写入小程序源码、README 或 Git。

## 本地联调

在仓库根目录运行 `一键启动.cmd`，按启动窗口显示的地址联调。开发者工具与后端位于同一台电脑时，可使用 `127.0.0.1:18080` 作为后端地址。

真机需要与电脑处于同一局域网。启动器会动态检测当前电脑的局域网 IPv4，并供局域网转发使用；不要使用固定网段或固定动态 IP。在微信开发者工具中勾选“不校验合法域名”。

`utils/network-config.js` 由首次初始化脚本或启动器在本地生成，并已被 Git 忽略。网络变化后应重新启动本地服务，在开发者工具中重新编译，再发起真机调试；只更换 IP 不需要重新构建 npm。

## 设计与数据边界

- 主色和设计变量见 `styles/tokens.wxss`。
- 复用组件位于 `components/`。
- 请求与头像上传统一使用 `utils/config.js` 的 `baseUrl`。
- `mock/` 只保留静态展示元数据，不包含真实业务数据或支付凭据。

首次生成的配置默认关闭真实微信支付，已有本地配置保持原样。`.local/wechat-pay.properties` 由首次安装脚本补齐，仓库不提供老师、商户或其他第三方支付凭据。需要支付联调时，请使用使用者自己取得授权的配置。

完整安装步骤见 [首次安装与 GitHub 上传](../../docs/首次安装与GitHub上传.md)。
