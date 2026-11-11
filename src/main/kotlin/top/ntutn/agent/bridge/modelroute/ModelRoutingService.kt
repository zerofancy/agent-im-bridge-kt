package top.ntutn.agent.bridge.modelroute

import java.nio.file.Path
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

/**
 * 按聊天隔离的档位路由服务，bridge 在「出队投递前」调用 [beginTurn] 取本轮模型。
 *
 * 每个聊天一个 [ModelRouter]，状态落在 [root] 下按 chatId 哈希命名的文件里，
 * 重启后沿用上次档位；写入失败只记日志，不阻断回复。
 */
class ModelRoutingService(
    private val root: Path,
    private val probe: ModelHealthProbe,
    private val ladder: ModelLadder = DefaultLadder.ladder(),
    private val config: RouterConfig = RouterConfig(),
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger("top.ntutn.agent.bridge")
    private val mutex = Mutex()
    private val routers = mutableMapOf<String, ModelRouter>()

    /** 本轮应使用的模型 ID；同时完成自动升档判定并标记运行中。 */
    suspend fun beginTurn(chatId: String): String =
        withRouter(chatId) { router ->
            router.onUserMessage(Instant.now(clock))
            router.onReplyStart()
            ladder.tierAt(ladder.indexOf(router.state.currentTierId)).providerModelId
        }

    /** 一轮正常结束：回填上下文用量并重新起算空闲时钟。 */
    suspend fun endTurn(chatId: String, contextTokens: Int? = null) {
        withRouter(chatId) { router ->
            if (contextTokens != null) {
                router.updateContextTokens(contextTokens)
            }
            router.onReplyEnd(Instant.now(clock))
            Unit
        }
    }

    /**
     * 用户中断后的收尾，必须等当前轮到达终态再调用。
     *
     * @param wasQueued 中断发生时是否仍在排队；非排队中断只落空闲锚点，不降档。
     */
    suspend fun stopTurn(chatId: String, wasQueued: Boolean) {
        withRouter(chatId) { router ->
            router.onUserStop(Instant.now(clock), wasQueued)
            Unit
        }
    }

    /** 当前聊天选中的档位，仅供诊断。 */
    suspend fun currentTier(chatId: String): String =
        withRouter(chatId) { router -> router.state.currentTierId }

    private suspend fun <T> withRouter(chatId: String, block: suspend (ModelRouter) -> T): T {
        val router = mutex.withLock { routers.getOrPut(chatId) { loadRouter(chatId) } }
        val result = block(router)
        persist(chatId, router)
        return result
    }

    private suspend fun loadRouter(chatId: String): ModelRouter =
        withContext(Dispatchers.IO) {
            val state =
                try {
                    StateStore.load(fileFor(chatId).toFile(), ladder)
                } catch (e: Exception) {
                    log.warn("档位状态读取失败，使用初始状态；chatId={} reason={}", chatId, e.javaClass.simpleName)
                    StateStore.freshState(ladder)
                }
            ModelRouter(
                ladder = ladder,
                config = config,
                probe = HealthProbe { tier -> probe.modelHealth(tier.providerModelId) },
                initialState = state,
            )
        }

    private suspend fun persist(chatId: String, router: ModelRouter) {
        val state = router.state
        withContext(Dispatchers.IO) {
            try {
                StateStore.save(fileFor(chatId).toFile(), state)
            } catch (e: Exception) {
                log.warn("档位状态写入失败；chatId={} reason={}", chatId, e.javaClass.simpleName)
            }
        }
    }

    private fun fileFor(chatId: String): Path {
        val digest =
            MessageDigest.getInstance("SHA-256").digest(chatId.toByteArray()).joinToString("") {
                "%02x".format(it)
            }
        return root.resolve("$digest.properties")
    }
}
