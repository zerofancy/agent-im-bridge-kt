# 项目约定

## Kotlin 与并发

- 使用 Kotlin/JVM，保持 JDK 11 兼容，业务代码位于 `top.ntutn.agent.bridge`。
- 新增或重构异步业务时优先使用 `kotlinx.coroutines`。聊天调度、排队、消息回复等待使用协程，不使用自建线程池或每任务线程来调度业务。
- 使用有明确生命周期的 `CoroutineScope` 和结构化并发；独立聊天任务通过 `SupervisorJob` 隔离失败。禁止 `GlobalScope` 和脱离所属服务生命周期的后台任务。
- Java SDK 的 `CompletableFuture` 在边界保留，协程内使用 `await()`；不要通过 `get()`、`join()` 阻塞协程线程。同步 `AutoCloseable.close()` 等应用边界可以使用 `runBlocking`，不得从服务自身的协程调用关闭入口。
- 阻塞 CLI/文件操作放在 `Dispatchers.IO`；可中断的阻塞调用使用 `runInterruptible`。现有 CodexRunner 的同步进程接口由该边界适配，后续重构其内部 I/O 时也优先使用协程。
- 不吞掉协程取消异常。关闭时先停止接收与调度，再取消子任务、终止进程树并等待清理；不得继续消耗队列，待处理请求对象必须结束。
- 协程业务的共享状态使用 `Mutex.withLock` 或通过 `Channel` 串行管理，避免 `synchronized`、`ReentrantLock` 等线程锁；临界区不得执行外部 I/O。同步 SDK 入口使用非阻塞协程桥接，不通过 `runBlocking` 获取业务锁。
- 取消后的必要状态清理使用 `NonCancellable`，保证释放聊天运行槽位并结束请求对象；不得在该区域继续执行业务任务。

## 必须保留的行为

- 同一 `chatId` 严格 FIFO 串行，不同聊天允许并发，受 `--max-concurrent-runs` 限制。不能依靠协程调度顺序来保证 FIFO。
- 会话按应用、聊天、规范化工作目录和 Codex 状态目录隔离并持久化；队列只存在内存。
- 私聊及群聊均校验 `allowedUserId`；群聊还必须明确 @机器人。保留只读权限、SDK 去重和原消息回复路由。
- 日志不记录消息正文、凭证或完整外部授权响应。

## 验证

- 行为变更运行 `./gradlew test installDist`。
- 并发改动验证同聊天串行、跨聊天并发、并发上限、失败后出队、等待发送时取消、关闭后不再运行排队任务及进程清理。
- 真实 Codex 测试使用本机登录账号，按需显式设置 `CODEX_LIVE_TEST=1`，不纳入默认离线测试。
