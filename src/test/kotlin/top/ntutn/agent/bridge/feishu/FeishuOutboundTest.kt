package top.ntutn.agent.bridge.feishu

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir
import top.ntutn.agent.bridge.BackendId
import top.ntutn.agent.bridge.BackendSpec
import top.ntutn.agent.bridge.SplitCardReplies
import top.ntutn.agent.bridge.storage.BridgeConfig
import top.ntutn.agent.bridge.storage.SessionStore

class FeishuOutboundTest {
    @TempDir lateinit var temp: Path

    @Test
    fun `doubao on feishu uses one-card replies instead of split delivery`() {
        val replies =
            createFeishuReplies(
                config =
                    BridgeConfig(
                        "cli_test",
                        "secret",
                        "ou_test",
                        "feishu",
                        backend = "doubao",
                    ),
                client = { error("unused") },
                answers = CardAnswerStore(temp.resolve("answers")),
                fileLinks = null,
                environmentDirectory = temp.resolve("env"),
                appDirectory = "app",
                sessions = SessionStore(temp.resolve("sessions.json")),
                backend = BackendSpec(BackendId.DOUBAO, "doubao", temp.resolve("runtime")),
                workspace = temp.resolve("workspace"),
            )
        assertFalse(replies is SplitCardReplies)
    }

    @Test
    fun `standard cardkit mode keeps split delivery for normal Feishu flows`() {
        val replies =
            createFeishuCardReplies(
                config = BridgeConfig("cli_test", "secret", "ou_test", "feishu"),
                client = { error("unused") },
                answers = CardAnswerStore(temp.resolve("answers")),
                fileLinks = null,
                cotDirectory = temp.resolve("cot"),
                progressMode = "cardkit",
            ) { null }
        assertTrue(replies is SplitCardReplies)
    }
}
