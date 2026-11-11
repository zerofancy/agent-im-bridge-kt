package top.ntutn.agent.bridge.modelroute

import java.nio.file.Path
import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import top.ntutn.agent.bridge.AgentResult
import top.ntutn.agent.bridge.createModelRouting
import top.ntutn.agent.bridge.AgentRunner
import top.ntutn.agent.bridge.BackendId
import top.ntutn.agent.bridge.BackendSpec
import top.ntutn.agent.bridge.SandboxMode
import top.ntutn.agent.bridge.storage.BridgeConfig

class ModelRoutingFactoryTest {

    private object ProbeRunner : AgentRunner, ModelHealthProbe {
        override suspend fun run(
            prompt: String,
            sessionId: String?,
            workspace: Path,
            sandboxMode: SandboxMode,
            onSession: suspend (String) -> Unit,
        ): AgentResult = AgentResult.Failure(AgentResult.Kind.EXECUTION)

        override suspend fun modelHealth(modelId: String): TierHealth? = null

        override fun close() {
            Unit
        }
    }

    private fun backend(id: BackendId): BackendSpec =
        BackendSpec(id, "/bin/true", Path.of("/tmp"), Path.of("/tmp"))

    private fun config(traexModelTiering: Boolean = true): BridgeConfig =
        BridgeConfig(
            "cli",
            "secret",
            "ou_owner",
            "feishu",
            backend = "traex",
            traexModelTiering = traexModelTiering,
        )

    @Test
    fun `routing is created only for traex with the switch on`() {
        assertNotNull(
            createModelRouting(config(), backend(BackendId.TRAEX), ProbeRunner, Path.of("/tmp")),
            "Traex + 开关开启时才启用",
        )
        assertNull(
            createModelRouting(
                config(traexModelTiering = false),
                backend(BackendId.TRAEX),
                ProbeRunner,
                Path.of("/tmp"),
            ),
            "开关关闭时不启用",
        )
        assertNull(
            createModelRouting(config(), backend(BackendId.CODEX), ProbeRunner, Path.of("/tmp")),
            "非 Traex 后端不启用",
        )
    }

    @Test
    fun `runner without model health support is not routed`() {
        val plain =
            object : AgentRunner {
                override suspend fun run(
                    prompt: String,
                    sessionId: String?,
                    workspace: Path,
                    sandboxMode: SandboxMode,
                    onSession: suspend (String) -> Unit,
                ): AgentResult = AgentResult.Failure(AgentResult.Kind.EXECUTION)

                override fun close() {
                    Unit
                }
            }
        assertNull(
            createModelRouting(config(), backend(BackendId.TRAEX), plain, Path.of("/tmp")),
            "不支持 model/list 的 runner 不启用",
        )
    }
}
