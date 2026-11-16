# 首次安装与 GitHub 上传

本文面向准备在 Windows 上首次运行 AIhealthcare、随后将源码上传到 GitHub 的使用者。所有命令均从仓库根目录执行，不依赖固定盘符、用户名或固定局域网地址。

## 1. 环境准备

请先确认以下工具可用：

- Windows PowerShell 5.1 或 PowerShell 7
- Java 21 及以上
- Maven
- Node.js 20.19 及以上或 22.12 及以上
- MySQL 8，监听本地 `3306`
- Python 3.10 及以上，且可使用 `python -m pip`
- 一份来源可信、已有合法使用授权的 Windows Redis 程序目录

检查示例：

```powershell
$PSVersionTable.PSVersion
java -version
mvn -version
node --version
npm --version
python --version
python -m pip --version
```

MySQL 需要处于运行状态。安装脚本和初始化脚本只使用本地 MySQL `127.0.0.1:3306`，不连接远程业务数据库或外部支付服务。

## 2. 生成本地配置

在 Windows PowerShell 5.1 中运行：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\setup-local.ps1 -RedisSourceDirectory 'D:\path\to\trusted\redis'
```

在 PowerShell 7 中也可以运行：

```powershell
pwsh -NoProfile -File .\scripts\setup-local.ps1 -RedisSourceDirectory 'D:\path\to\trusted\redis'
```

`-RedisSourceDirectory` 指向使用者已有且可信、支持 Windows 的 Redis 程序目录，其中应有 `redis-server.exe`、`redis-cli.exe` 及其配套 DLL。只有项目 `.tools/redis/` 已具备这些程序时才可省略参数。脚本只复制这些程序和 DLL，不复制源目录的密码、配置或持久化数据，也不会下载或自动执行第三方程序。仓库不包含 Redis 二进制。

应自行从所用发行版的发布方获取程序，并核对其签名或公开校验值；不要从未知网盘下载。可以先用 `Get-FileHash <程序路径> -Algorithm SHA256` 记录所使用的版本。首次配置不启动 Redis；启动器随后会校验回环监听、专属数据目录和密码一致性。

首次调用只补齐以下本机配置：

- `.local/backend.properties`
- `.local/redis.conf`
- `.local/wechat.properties`
- `.local/wechat-pay.properties`

脚本会为本地数据库和 Redis 生成随机密码，不会覆盖已经存在的配置，也不会写入数据库。微信支付在默认配置中为 disabled。重新运行前先确认项目是否已经初始化；已有本地项目应跳过首次初始化。

以上文件包含本机凭据，必须保持在被 Git 忽略的 `.local/` 中，不要复制到 README、聊天记录或公开 Issue。

## 3. 安装数据库初始化依赖

```powershell
python -m pip install -r .\scripts\requirements-setup.txt
```

该步骤只安装初始化脚本使用的 Python 依赖。

## 4. 初始化本地数据库

```powershell
python .\scripts\init-database.py
```

脚本会隐藏输入 MySQL 管理密码，并尝试创建：

- 本地数据库 `aihealthcare_local`
- 数据库账号 `aihealthcare_app`

数据库初始化只允许尚不存在的目标。只要目标数据库或目标账号已经存在（即使库为空），脚本就会拒绝覆盖；不要用删除现有数据库的方式强行绕过。已经完成初始化时，直接使用现有本地配置，不要重复运行此步骤。MySQL DDL 不支持整批事务回滚；如果创建中途失败，脚本保留新建对象供管理员检查，不会自动删除库或账号。

初始化失败时终端只显示 MySQL 数字错误码，不显示密码或完整 SQL。恢复前应由管理员核对本次确实新建了哪些对象及其是否仍有用，只有确认属于本次失败安装的对象才可手工清理；不要清理原项目或已有业务库。首次配置生成的随机密码包含大小写、数字和特殊字符，以兼容常见的 MySQL MEDIUM 策略。

`database/schema.sql` 只包含结构，不含真实业务数据。`database/demo-seed.sql` 仅包含合成菜单、科室和药品；`admin` 账号由脚本参数化创建，初始管理员密码通过隐藏输入设置。初始化不会复制原项目的患者、微信身份、预约、账本或历史交易。医生与配药师仍由管理员在页面创建，员工初始密码保持 `123`，可以自行改密。

## 5. 安装前端依赖

管理端：

```powershell
Set-Location .\admin-web
npm ci
Set-Location ..
```

微信小程序：

```powershell
Set-Location .\wechat-miniprogram\miniprogram
npm ci
npm run build:npm
Set-Location ..\..
```

`npm ci` 会依据各自目录中的 `package-lock.json` 安装依赖。小程序命令会生成 `miniprogram_npm`，该构建目录不应提交到 GitHub。

## 6. 设置微信开发者信息

用微信开发者工具导入仓库内的 `wechat-miniprogram/`。公开上传前，将以下两份配置中的 AppID 换成使用者自己的 AppID，并确认值一致：

- `wechat-miniprogram/project.config.json`
- `wechat-miniprogram/miniprogram/project.config.json`

AppID 当前不是密钥，但它应与使用者本人的小程序项目匹配。AppSecret 绝不能写入这两个文件；本地 AppSecret 只放在 `.local/wechat.properties`。

`.local/wechat-pay.properties` 默认保持支付关闭。仓库不能提供老师、商户或其他第三方的支付凭据。真实支付联调必须由使用者使用自己合法取得的配置完成。

`wechat-miniprogram/miniprogram/utils/network-config.js` 由本地初始化脚本或启动器生成，并已被 Git 忽略。启动后以程序窗口显示的当前局域网 IPv4 为准，不要把固定网段写入源码。

## 7. 启动项目

双击仓库根目录的 `一键启动.cmd`，或在 PowerShell 中运行：

```powershell
.\一键启动.cmd
```

启动窗口会显示本机和局域网地址。常用本机入口如下：

- 管理端：`http://127.0.0.1:15173/login`
- 后端：`http://127.0.0.1:18080`
- MySQL：`127.0.0.1:3306/aihealthcare_local`
- Redis：`127.0.0.1:16379`

局域网管理入口中的 IPv4 是动态值，应以本次启动窗口为准。网络变化后重新启动项目，并重新生成小程序网络配置。

## 8. 上传前检查

先在 GitHub 创建空白仓库。按当前约定，Git 操作由使用者完成；本文只说明本地初始化、暂存、状态检查和提交，不包含远程推送命令。

```powershell
git init
git add .
git status --short
git commit -m "Initial commit"
```

`git status --short` 后请人工检查准备提交的文件。至少确认：

- 不包含 `.local/`、`.runtime/`、`.tools/` 中的凭据、Redis 程序和运行数据。
- 不包含 `node_modules/`、`target/`、`dist/`、`miniprogram_npm/` 等依赖或构建产物。
- 不包含真实业务数据库导出、日志、用户上传文件或个人隐私截图。
- 不包含微信 AppSecret、微信支付商户凭据、数据库 root 密码或其他第三方秘密。
- 两份 `project.config.json` 使用使用者自己的 AppID。
- 已阅读 `THIRD_PARTY_NOTICES.md`，确认原工程和素材的来源与授权。
- 仓库中没有被误加入的真实数据 seed 或历史数据库快照。

`.gitignore` 只阻止匹配文件被 Git 跟踪，不是整目录打包过滤器。被忽略的文件仍可能存在于本地发布压缩包中；Git 已经跟踪过的文件也不会因为之后加入忽略规则而自动停止提交。因此不能只依赖 `.gitignore`，必须检查本地发布包和 Git 状态。

提交完成后，由仓库所有者确认远程仓库、目标分支和可见性，再自行完成推送。不要在未经确认时把本地历史、支付材料或个人数据上传到公开仓库。

## 9. 许可与第三方说明

当前尚未选择 `LICENSE`。在明确选择并添加许可证前，不能宣称项目采用 MIT、Apache-2.0 或其他开放许可，也不能据此向他人授予再分发或衍生权利。

代码来源和待确认的许可范围记录在 `THIRD_PARTY_NOTICES.md`。本次没有替原作者选择开源许可证，依赖自带的 LICENSE、NOTICE 和作者信息需要保留。
