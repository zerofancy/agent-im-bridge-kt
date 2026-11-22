# 项目约定

## Kotlin 代码风格

- Kotlin 源码、测试与 Gradle Kotlin DSL 统一使用 4 空格缩进。声明和控制结构的左花括号 `{` 与声明或条件结束部分同一行，不另起一行；使用 `} else {`、`} catch (...) {`、`} finally {`。
- `if`、`else`、`for`、`while`、`do/while` 的主体必须使用显式 `{ ... }`，即使只有一条语句、一次 return 或一个变量表达式也不得省略。`if` 作为返回值、参数或变量初始化表达式时同样适用。
- `when` 的每个分支使用 `-> { ... }`，即使分支只返回一个变量或常量。`else if (...) { ... }` 保留标准链式写法。
- 控制结构的块按多行展开，不将多个语句压在同一行。函数的 Kotlin 表达式体与尾随 lambda 语法可以保留；不得借此省略内部控制结构的花括号。
- 全量格式化采用 ktfmt 0.59 的 Kotlin Lang 风格；格式化工具不会自动补齐所有花括号，修改后仍需检查控制结构。字符串和注释中的示例不按控制语句改写。

## Kotlin 与并发

- 使用 Kotlin/JVM，保持 JDK 21 兼容，业务代码位于 `top.ntutn.agent.bridge`。
- 新增或重构异步业务时优先使用 `kotlinx.coroutines`。聊天调度、排队、消息回复等待使用协程，不使用自建线程池或每任务线程来调度业务。
- 使用有明确生命周期的 `CoroutineScope` 和结构化并发；独立聊天任务通过 `SupervisorJob` 隔离失败。禁止 `GlobalScope` 和脱离所属服务生命周期的后台任务。
- Java SDK 的 `CompletableFuture` 在边界保留，协程内使用 `await()`；不要通过 `get()`、`join()` 阻塞协程线程。同步 `AutoCloseable.close()` 等应用边界可以使用 `runBlocking`，不得从服务自身的协程调用关闭入口。
- 阻塞 CLI/文件操作放在 `Dispatchers.IO`；可中断的阻塞调用使用 `runInterruptible`。CodexRunner 使用挂起接口及结构化协程管理独立 app-server 的 stdio RPC；公共读取生命周期不依附单个聊天。
- 不吞掉协程取消异常。整个 Bridge 关闭时先停止接收与调度，请求原生中断，最多等待 5 秒后终止自身 app-server 进程树并等待清理；不得继续消耗队列，待处理请求对象必须结束。
- 协程业务的共享状态使用 `Mutex.withLock` 或通过 `Channel` 串行管理，避免 `synchronized`、`ReentrantLock` 等线程锁；业务调度临界区不得执行外部 I/O；存储事务允许在 `Dispatchers.IO` 上持有写入 Mutex，以保证原子落盘与内存快照一致。同步 SDK 入口使用非阻塞协程桥接，不通过 `runBlocking` 获取业务锁。
- 取消后的必要状态清理使用 `NonCancellable`，保证释放聊天运行槽位并结束请求对象；不得在该区域继续执行业务任务。存储原子提交同样需要保护磁盘替换及对应内存更新，避免取消造成两者不一致。

## 必须保留的行为

- 同一 `chatId` 严格 FIFO 串行，不同聊天允许并发，受 `--max-concurrent-runs` 限制。不能依靠协程调度顺序来保证 FIFO。
- 会话按应用、聊天、规范化工作目录和 Codex 状态目录隔离并持久化；队列只存在内存。
- 私聊及群聊均校验 `allowedUserId`；群聊还必须明确 @机器人。默认只读；访问模式仅由本机配置 `sandboxMode` 决定，重启生效。保留 SDK 去重和原消息回复路由。
- `/status`、`/pwd`、`/help`、`/stop`、`/cd` 是即时控制命令，不进入模型队列、不占用模型槽位；未知斜杠命令原样交给 Agent。
- `/cd` 必须带路径，当前聊天忙碌时拒绝切换。切换不同目录会清除目标目录旧绑定，切回也新建上下文。目录选择持久化，切换成功但回复失败不回滚。
- `/stop` 取消当前聊天运行任务和命令接收时已有的队列；新请求等待清理结束后运行。按请求身份释放槽位，不影响其他聊天。
- 模型任务没有自动超时或无进展超时。保留外部发送、连接和进程清理的技术超时。
- CLI 会话发现回调为挂起函数，必须等存储落盘后再继续读取事件；取消时也必须等回调清理结束。
- 日志不记录消息正文、凭证或完整外部授权响应。

## 验证

- 行为变更运行 `./gradlew test installDist`。
- 并发改动验证同聊天串行、跨聊天并发、并发上限、失败后出队、等待发送时取消、关闭后不再运行排队任务及进程清理。
- 真实 Codex 测试使用本机登录账号，按需显式设置 `CODEX_LIVE_TEST=1`，不纳入默认离线测试。

## Codex 原生会话控制

- 每个 Bridge 实例使用独立 app-server，不连接或停止桌面端服务，不回退到 exec。按 RPC ID 匹配响应，按 thread ID 和 turn ID 路由事件。
- `/stop` 使用独立执行句柄记录停止意图，调用 `turn/interrupt`；不得通过取消公共读取协程或杀进程实现正常聊天停止。必须等当前轮终态，不能把 RPC 接受响应当作已停止。
- 中断 30 秒未确认时提示异常并持续占槽，不自动强杀；重复停止复用当前过程，保留停止后新入队的请求。整个 Bridge 退出才允许兜底清理进程。
- 先持久化会话 ID，再提交 turn/start；每轮显式指定 cwd、approvalPolicy=never、sandboxPolicy。仅从本轮完成的 agentMessage 提取最终答案，过滤 commentary 与工具输出。
- 连接失效但服务仍存活时，不释放结果不明的任务槽位，不自动重跑。日志不包含完整 RPC 载荷或服务端错误正文。
- 旧式 build/install 运行实例必须先停止才可 installDist。新的运行实例必须来自不可变发布快照；构建可与快照实例并行，但升级只使用 bridgectl publish/deploy，禁止修改 releases 内已有产物。各项退出清理用 try/finally 保证独立执行。

## 部署与环境隔离

- 启动外部程序时必须显式指定业务所需的工作目录；相对路径读写必须显式指定并解析基准目录，不得依赖 Bridge/JVM 进程的当前目录隐式推断。聊天工作目录与进程 cwd 是独立概念，不能仅通过命令参数传入项目路径来替代子进程工作目录设置。

- macOS 正式运行由用户级 launchd 守护，Linux 由用户级 systemd 守护，使用 `bridgectl`；不要直接执行 build/install 的 JVM 启动脚本，也不要用 nohup 绕过守护。
- `dev` 与 `prod` 的机器人、配置、模型状态、锁、附件、日志和默认工作目录必须独立。正式操作显式指定 `--env prod`；不得把调试凭据或状态提升到正式环境。
- 发布目录为不可变快照，JVM classpath 必须使用真实版本目录，不能包含 current 链接。升级通过独立部署执行器异步完成，机器人任务只提交部署编号，不同步等待自身排空。
- 未捕获内部异常和 JVM Error 使用统一 FatalErrorHandler 退出整个进程，不在损坏的 JVM 中强行恢复槽位。明确的外部操作失败仍在边界转换为可恢复失败；禁止用宽泛 catch 隐藏内部编程异常。
- 对来源不明、无法可靠分类的外部请求失败，允许直接抛出并交给 FatalErrorHandler 退出进程，由守护进程拉起恢复；前提是日志必须保留足够的脱敏诊断信息以便排查。
- 启动提示在通道首次连接成功后主动发送到 allowedUserId 对应的机器人私聊，包含上次退出原因；整个进程最多尝试一次，失败或发送未知不重试，不等待首条用户消息、不发到群聊或文档评论、不阻塞普通回复。所有文字回复走统一发送入口。生命周期记录不得输出消息正文或外部错误正文，无法确定的退出原因标记未知。
- `./gradlew test installDist` 包含部署单元测试。真实故障注入只使用 `deployment/live_launchd_test.py`（macOS）或 `deployment/live_systemd_test.py`（Linux）的临时环境，不在正式机器人上注入崩溃。
- systemd 服务文件位于 `~/.config/systemd/user/`，与 launchd plist 保持相同的生命周期管理。

- 首次 `bridgectl dev` 在前台终端交互选择或创建测试机器人，自动准备工作目录和模型目录，并按需引导独立模型登录；不要求用户手写配置。守护重启不得触发交互授权，损坏配置不得自动覆盖。

## Android 正式分发（zp.ntutn.top）

- 发布需用户明确授权；仅构建不代表允许上传或提交公开分发。软件建档及 Token 签发通过服务器管理后台完成，不猜测软件内部名。
- 从被 Git 忽略的 `android-app/local.properties` 读取 `updaterPublishBaseUrl`（当前 `https://zp.ntutn.top`）、`updaterPublishSoftware`（当前 `agent-im-bridge-kt`）和 `updaterPublishToken`。BaseUrl 是站点根地址，发布前缀拼接为 `/api/v1/publish/{software}`；不要重复拼接路径。配置文件权限保持 `0600`，修改非敏感字段时保留已有 Token。
- Token 只在内存读取，以单个 `Authorization: Bearer` 请求头发送到配置的 HTTPS 发布服务；不输出配置全文，不放入命令参数、URL、日志、APK、BuildConfig 或仓库。预签名 URL 同样是临时凭据，不输出或提交。不要盲目跟随重定向转发凭据。
- 发布前核对服务器当前协议：`https://zp.ntutn.top/api`。某些默认 Python User-Agent 会被网关拒绝；可使用 `Mozilla/5.0 (compatible; AgentBridgeRelease/1.0)`，区分网关非 JSON 错误和 API JSON 错误，不将 403 当作发布成功。
- 签名使用 gradle-keyring 0.2，复用 Kica 的长期 release keystore 和别名，配置与密码初始化见 `docs/android-signing.md`。本项目系统密钥环服务名为 `Gradle Project android-app`；不把密码写入属性文件，签名构建强制禁用 configuration cache。

### 构建与发布前核对

1. 执行 `./gradlew -p android-app --no-configuration-cache --console=plain verifyReleaseSigning testDebugUnitTest assembleRelease bundleRelease`。从 APK 的实际 manifest 读取 applicationId、versionName、versionCode，不能只根据文件名猜测；正式升级须递增 versionCode。
2. 待分发 APK 为 `android-app/build/outputs/apk/release/android-app-release.apk`；AAB 位于 `android-app/build/outputs/bundle/release/android-app-release.aab`，默认只分发可直接安装的 APK。保留 R8 mapping。
3. 使用 SDK Build Tools 的 `apksigner verify --verbose --print-certs` 验证 APK。长期签名证书 SHA-256 为 `7202b06d2a5842ac07f7d246dde8850f55963ff7d004b0e3e47e8c847532538f`，必须一致；计算最终 APK 完整字节的大小和 SHA-256。
4. 发布前调用公开接口 `GET /api/v1/updates?software={software}&os=Android&packageType=apk` 核对已有版本。首发可能返回 `404 NO_MATCHING_PACKAGE`，不要把 `SOFTWARE_NOT_FOUND`、认证失败或其他异常当作首发空记录。
5. 检查 APK 实际 ABI。当前为通用 APK；分发 API 只支持 ARM64 / X86_64 两种架构枚举，登记 `[{"os":"Android","arch":"ARM64"},{"os":"Android","arch":"X86_64"}]`，不要传入 armeabi-v7a、x86 或自行添加其他枚举。

### R2 上传与原子提交

1. 每次逻辑上传、每次逻辑分发分别生成小写标准 UUID 幂等键。在首次请求前持久保存键、完整请求 JSON、产物摘要；上传授权回执以 `0600` 保存。结果未知时必须保留原键及原始请求字节，不换键、不改标题绕过冲突；新版本使用独立记录。请求接受 UTF-8 JSON，64 位大小、versionCode 和 ID 按十进制字符串处理。
2. `POST {发布前缀}/uploads`，携带 `Idempotency-Key`，提交 `filename`、`size`、`checksumAlgorithm: "sha256"` 和实际 `checksumValue`。保存返回的 uploadId、method、headers、uploadUrl 与期限；响应为 201 或同键重放 200。
3. 按返回的 PUT URL 和 headers 将 APK 原始字节直传 R2，通常为 `Content-Type: application/octet-stream`。先验证 URL 为 HTTPS 及可信 R2 主机；绝不携带发布 Bearer，不自动跟随重定向。
4. `POST {发布前缀}/uploads/{uploadId}/complete`，请求体 `{}`。只有状态 `READY` 才可首次关联；202 / VERIFYING 按 Retry-After 有界退避查询 `GET .../uploads/{uploadId}`，持续等待时保留状态供后续恢复。上传响应丢失时先查询/完成校验，确认 OBJECT_NOT_FOUND 后才补传；授权过期不得盲目重用。
5. `POST {发布前缀}/distributions`，使用独立 `Idempotency-Key`，提交 `title`、真实版本说明及 `packages`。每包含 name、`packageType: "apk"`、versionCode、versionName、targets、`source: "r2"`、uploadId；R2 包不能额外传 downloadUrl、packageSize 或 checksum 字段。首次 201、同键同体重放 200；提交成功立即公开，上传成功不等于发布成功。
6. 保存回执中的 distributionId、packageId、downloadUrl、大小及摘要。网络超时/可重试错误按服务端 Retry-After 使用原键原请求确认；401/403、版本冲突、幂等冲突先诊断，不盲目循环或重新发布。服务器不实测全文件 checksum，READY 不代表摘要已验证。
7. 发布后重新查询公开更新接口，确认 ARM64 / X86_64 均指向预期版本及分发；无 Token 回下载公开 APK，核对大小、SHA-256、与本地产物逐字节一致，并再次执行 apksigner 验证。向用户返回版本、分发编号和公开下载链接，不包含任何凭据。

### 已完成首发与恢复记录

- 首个 Android 正式分发：`1.1.0`（versionCode `2`），软件 `agent-im-bridge-kt`，distributionId `19`，packageId `25`，北京时间 2026-11-23 发布。沿用当前版本而非将“首发”擅自重置为 1.0.0 / 1。
- 首发 APK SHA-256：`97a7e720c136ee6f3dd8e4333bea1ce72ffe59c231939b93b3fb84c4e8de8c04`；公开地址 `https://shared-files.ntutn.top/20261123/assets/5576d21a-72fa-4a92-b1e2-c0a19a83c263/agent-bridge-1.1.0-android-universal.apk`，回下载与签名均已验证。
- 首发一次性脚本为被忽略的 `build/publishing/publish_first.py`，幂等请求、授权及回执在 `build/publishing/android-1.1.0-2/`。这些不是受版本控制的通用发布工具，clean 会删除；不要依赖其永久存在，不把授权文件提交到 Git，也不要拿硬编码首发版本的脚本发布新版本。待确认完成前不得清理恢复记录。

## 飞书接入倾向

- 飞书接入优先采用“双轨”策略：事件接收可暂时保留官方 SDK，但新增加或重构的出站 OpenAPI 调用优先使用本项目自建的 HTTP 封装，逐步减少对飞书 SDK 的依赖。
- 需要复用现有鉴权能力时，优先通过 SDK 暴露的 token manager 获取和刷新 token；不要依赖 SDK 私有状态、反射内部字段或抓取其请求头。
- 新实现应避免继续扩大对 SDK 通用对象序列化、底层 `client.post/get/...` 和脆弱反射路径的依赖；遇到此类能力优先改为显式 JSON 请求和明确的错误处理。

## 文件规模

- 项目文本文件默认不超过 500 行，包含空行与注释；检查范围为 Git 已跟踪文件及未被忽略的新增文件，跳过二进制文件、符号链接和已删除文件。
- 优先按职责拆分，不通过压缩代码、删除必要说明或修改忽略规则规避限制。
- 个别文件可在 `.codex/file-line-exceptions.json` 按精确相对路径登记 `reason` 和 `max_lines`；豁免仍有上限，禁止目录或通配豁免。存量豁免不意味着允许继续增长。
- 完成修改前运行 `python3 scripts/check_file_lines.py`。Codex 项目级 Stop hook 使用同一检查器；超限时要求继续修复。
- 首次启用或修改 `.codex/hooks.json` 后，在可信项目中通过 Codex CLI 的 `/hooks` 审阅并信任；未信任的 hook 不会执行。已有会话可能需要重新加载配置或重开。
