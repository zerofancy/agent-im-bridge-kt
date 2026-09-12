# 项目约定

## Kotlin 与并发

- 使用 Kotlin/JVM，保持 JDK 11 兼容，业务代码位于 `top.ntutn.agent.bridge`。
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

- macOS 正式运行由用户级 launchd 守护，Linux 由用户级 systemd 守护，使用 `bridgectl`；不要直接执行 build/install 的 JVM 启动脚本，也不要用 nohup 绕过守护。
- `dev` 与 `prod` 的机器人、配置、模型状态、锁、附件、日志和默认工作目录必须独立。正式操作显式指定 `--env prod`；不得把调试凭据或状态提升到正式环境。
- 发布目录为不可变快照，JVM classpath 必须使用真实版本目录，不能包含 current 链接。升级通过独立部署执行器异步完成，机器人任务只提交部署编号，不同步等待自身排空。
- 未捕获内部异常和 JVM Error 使用统一 FatalErrorHandler 退出整个进程，不在损坏的 JVM 中强行恢复槽位。明确的外部操作失败仍在边界转换为可恢复失败；禁止用宽泛 catch 隐藏内部编程异常。
- 启动提示整个进程最多尝试一次；所有文字回复走统一发送入口。生命周期记录不得输出消息正文或外部错误正文，无法确定的退出原因标记未知。
- `./gradlew test installDist` 包含部署单元测试。真实故障注入只使用 `deployment/live_launchd_test.py`（macOS）或 `deployment/live_systemd_test.py`（Linux）的临时环境，不在正式机器人上注入崩溃。
- systemd 服务文件位于 `~/.config/systemd/user/`，与 launchd plist 保持相同的生命周期管理。

- 首次 `bridgectl dev` 在前台终端交互选择或创建测试机器人，自动准备工作目录和模型目录，并按需引导独立模型登录；不要求用户手写配置。守护重启不得触发交互授权，损坏配置不得自动覆盖。
