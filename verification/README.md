# 专项测试与真实 MySQL 并发验证

新增微信 Native 扫码通道的隔离复验入口为 `verification/run-verification.ps1 -NativePayments`，实现和使用说明见 [微信扫码支付接入](../docs/微信扫码支付接入.md)。该模式使用假微信网关及真实专属 MySQL，不会请求真实商户；商户通道实测有独立显式开关，不进入默认回归。

本套件验证现有实现，不修改业务逻辑。业务缺陷契约使用正常业务期望，当前的红色结果表示相应问题被核实。客户端明确标注 bugObserved 的测试属于现象复现，不能将其通过当成业务已修复。

F01 库存交付和 F02 充值幂等已完成修复及复验。仅验证这两项修复及其并发回归时，在项目根目录执行：

    powershell.exe -NoProfile -ExecutionPolicy Bypass -File verification/run-verification.ps1 -CriticalFixes

该模式运行 RealMysqlConcurrencyVerificationIT 与 CriticalPaymentFixVerificationIT，以及当前所有客户端专项测试。默认模式继续包含尚未整改的领域契约，因此可能因其他已知问题返回非零。历史缺陷复现的断言不会自动成为修复验收；F01/F02 已对应改成正确业务期望。

## 数据隔离

- MySQL 使用本机随机命名的 aihealthcare_verify_日期_后缀 库及 ahv_后缀 账号，账号仅有该测试库权限。
- 仅复制业务表结构，不复制患者、账号、订单等真实记录；所有数据均为合成数据。
- 凭据保存到被忽略的 .local/verification-mysql.properties，不保存管理密码。
- 测试不扫描应用配置、不修改生产隔离守卫，自行验证精确库名、专属账号及随机运行标识。
- 每次测试重置前检查连接库及工作线程状态；未退出的线程会污染整个验证上下文，禁止后续清库。
- 默认 12 并发线程，各自在事务中取得独立 MySQL 连接后，通过屏障一起放行；核心场景重复 3 轮，其他场景在结果中单独记录轮次。
- 真实 SQL 故障由测试库专属触发器 SIGNAL 注入；回滚断言同时核对 SQLState 45000 和特定故障消息。
- Redis 是隔离内存替身，不接运行中的 Redis，不宣称已验证 Redis TTL、限流或分布式行为。

## 准备测试库

已有本次生成的 .local/verification-mysql.properties 时可直接运行。新环境先在项目 .runtime/python 中准备 PyMySQL，并在终端临时设置 HEALTHCARE_VERIFY_ADMIN_PASSWORD，再运行：

    python verification/prepare-mysql.py --config .local/verification-mysql.properties --evidence .runtime/verification-initialization

不要把管理密码写入源码或聊天记录。初始化脚本拒绝覆盖已有配置，创建随机库和专属账号。测试库及合成数据保留供复现；清理时只能使用 database-isolation.json 中的确切名称，不能按通配符删除。

## 执行

    powershell.exe -NoProfile -ExecutionPolicy Bypass -File verification/run-verification.ps1

输出保存在 .runtime/verification-runs/时间戳，包含原始 Node/Maven 退出码、JUnit 和业务库前后只读散列。当前缺陷会使命令返回非零，这是需要解读的业务契约失败，不是“忽略失败后通过”。若运行期间有人操作业务库，前后散列也会变化，需要单独核对原因。

仅运行客户端：

    node --test --test-reporter=spec verification/client/client-state-regressions.test.cjs

仅运行 MySQL（先进入 backend）：

    mvn "-Dtest=RealMysqlConcurrencyVerificationIT,DomainContractVerificationIT" "-Dhealthcare.verify.enabled=true" "-Dhealthcare.verify.config=D:/cxdownload/AIhealthcare/.local/verification-mysql.properties" "-Dhealthcare.verify.rounds=3" test

普通 mvn test 默认不会选择 *IT。即使手动选择，缺少显式 enabled 开关时这些类也会禁用。原有单元测试与构建仍独立验证。

## 范围说明

F01 的契约允许在开方/付款时拒绝缺货请求，核心检查已收款均可交付或退款、净扣款与交付金额一致及库存守恒。F03 读取仓库实际的 SQL 日志配置，不硬编码危险实现。F11 仅记录映射失效时绑定被拒且文件是否保留，没有约定的清理任务和期限时不假定必须立即删除文件。

证据在 .runtime/verification-20261113，最终说明与问题核实结果在 docs 中。当前测试使用真实 MySQL 服务与生产 Service/Mapper/事务代理，但没有真实微信或银行付款，也不是吞吐压测。

本次最终结果与原始记录解读见 [专项测试与真实数据库并发验证](../docs/专项测试与真实数据库并发验证-20261113.md)。其中客户端最终 14/14 来自独立复跑；统一入口较早保存的 Client=1 仍保留，不修改原始退出码。

当前入口只归档修改时间不早于本次 Maven 启动时刻的 JUnit，并记录 JunitReportsCurrent。若 Maven 在编译或启动阶段失败，旧报告不会进入本次证据目录。
