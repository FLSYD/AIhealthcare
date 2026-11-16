# AIhealthcare 医院预约挂号系统

AIhealthcare 是一个由 Spring Boot 后端、Vue 3 管理端和微信小程序患者端组成的医院预约挂号项目。仓库提供可重复执行的本地安装脚本、不含真实业务数据的数据库结构和合成演示数据，适合本地开发与二次开发。

## 功能概览

- 患者端：科室与排班查询、预约挂号、订单、评价和 AI 导诊。
- 管理员：科室、医生、配药师、排班、药品、用户、菜单、日志与预约管理。
- 医生：本人排班和预约、接诊、病历、处方及完成就诊。
- 配药师：查看已付款处方、核对药品与发药记录，执行配药工作。

## 仓库结构

| 路径 | 用途 |
| --- | --- |
| `backend/` | Spring Boot 后端 |
| `admin-web/` | Vue 3 管理前端 |
| `wechat-miniprogram/` | 微信小程序外层项目与患者端源码 |
| `database/` | 数据库结构与合成演示数据 |
| `config-templates/` | 本地配置模板 |
| `scripts/` | 首次初始化、数据库初始化与局域网代理脚本 |
| `docs/` | 使用说明与历史整改快照 |
| `一键启动.cmd` | Windows 本地一键启动入口 |
| `start-local.ps1` | 启动 Redis、后端、管理端和局域网转发 |

`.local/`、`.runtime/`、`.tools/`、`node_modules/`、构建产物、日志和上传文件属于本机生成内容，不应提交到 GitHub。仓库不包含 Redis 二进制、真实业务数据库备份或真实支付凭据。

## 环境要求

- Windows PowerShell 5.1 或 PowerShell 7
- Java 21 及以上
- Maven
- Node.js 20.19 及以上或 22.12 及以上
- MySQL 8，本地端口 `3306`
- 用户自己提供并确认可信的 Windows Redis 程序目录
- Python 3.10 及以上与可用的 `python -m pip`

## 首次安装

完整步骤见 [首次安装与 GitHub 上传](docs/首次安装与GitHub上传.md)。最短流程如下：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\setup-local.ps1 -RedisSourceDirectory 'D:\path\to\trusted\redis'
python -m pip install -r .\scripts\requirements-setup.txt
python .\scripts\init-database.py

Set-Location .\admin-web
npm ci

Set-Location ..\wechat-miniprogram\miniprogram
npm ci
npm run build:npm

Set-Location ..\..
.\一键启动.cmd
```

使用 PowerShell 7 时，可将首条命令中的 `powershell.exe` 换成 `pwsh`。`setup-local.ps1` 只补齐首次使用的 `.local/backend.properties`、`redis.conf`、`wechat.properties` 和 `wechat-pay.properties`，不会覆盖已有配置，也不会写入数据库。它在提供 `-RedisSourceDirectory` 时从该可信目录复制 Redis `server`、`cli` 及依赖；仓库本身不提供 Redis 二进制。

`init-database.py` 通过隐藏输入获取 MySQL 管理密码，在本地创建 `aihealthcare_local` 数据库和 `aihealthcare_app` 账号。脚本只处理尚不存在的目标；已有同名数据库或账号会拒绝覆盖。`database/schema.sql` 不含真实数据，`database/demo-seed.sql` 只包含合成菜单、科室和药品；`admin` 账号由脚本另行创建，管理员密码通过隐藏输入设置，不写入源码。已经完成首次初始化的本地项目不要重复执行数据库初始化。

## 启动与访问

首次安装完成后，双击仓库根目录的 `一键启动.cmd`。启动窗口会显示本次使用的地址：

- 管理端：`http://127.0.0.1:15173/login`
- 后端：`http://127.0.0.1:18080`
- 局域网管理端：`http://<当前电脑局域网IPv4>:15173/login`
- MySQL：`127.0.0.1:3306/aihealthcare_local`
- Redis：`127.0.0.1:16379`

局域网地址由启动器根据当前网络动态检测。更换网络后请重新启动，以窗口显示的新地址为准。启动窗口按 `Q`、`Ctrl+C` 或关闭窗口会停止本次启动的服务；根目录 `停止服务.cmd` 也可请求正常退出。启动器不会停止其他项目使用的事务或缓存服务。

## 微信小程序

先完成小程序依赖安装，再用微信开发者工具导入仓库内的 `wechat-miniprogram/`，并勾选“不校验合法域名”以便本地联调。

微信 AppSecret 只能写入本机 `.local/wechat.properties`，不得提交到源码或 README。请将 `wechat-miniprogram/project.config.json` 和 `wechat-miniprogram/miniprogram/project.config.json` 中的 AppID 换成使用者自己的 AppID；AppID 当前不是密钥，但公开上传前仍应确认两份配置一致且属于使用者本人。

`wechat-miniprogram/miniprogram/utils/network-config.js` 由本地初始化脚本或启动器生成并被 Git 忽略。小程序通过该配置使用本次启动窗口显示的局域网地址；开发者工具同机调试也可使用 `127.0.0.1`。

首次生成的配置默认关闭真实微信支付；已有本地支付配置保持原样。`.local/wechat-pay.properties` 由首次安装脚本补齐，仓库不提供老师、商户或其他第三方的支付凭据。需要接入时，请使用使用者自己取得授权的配置。

## 构建

```powershell
Set-Location .\backend
mvn clean package

Set-Location ..\admin-web
npm ci
npm run build

Set-Location ..\wechat-miniprogram\miniprogram
npm ci
npm run build:npm
```

## 仓库边界与许可

`.gitignore` 只阻止匹配文件被 Git 跟踪，不是整目录打包过滤器，也不保证本地发布压缩包不包含被忽略内容；上传前仍需人工检查。公开前不得提交 `.local/` 密钥、真实业务数据、日志、用户上传、老师支付凭据或个人隐私截图。

当前仓库尚未选择 `LICENSE`，代码来源及待确认的许可状态见 [第三方说明](THIRD_PARTY_NOTICES.md)。请先确认原工程与素材授权，再选择适用许可证。Git 初始化、暂存、状态检查和提交步骤见 [首次安装与 GitHub 上传](docs/首次安装与GitHub上传.md)；本文不包含远程推送操作。
