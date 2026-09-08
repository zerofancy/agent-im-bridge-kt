package top.ntutn.agent.bridge

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

// Explicit opt-in: real model calls use the local user's authenticated Codex account.
@EnabledIfEnvironmentVariable(named = "CODEX_LIVE_TEST", matches = "1")
class CodexLiveTest {
    @Test fun `real CLI supports independent question and repository reading`() {
        CodexRunner("codex", Path.of("").toAbsolutePath(), Duration.ofSeconds(120)).use { runner ->
            runner.checkAvailable()
            val answer = assertIs<AgentResult.Success>(runner.run("只用中文回答：1 加 1 等于几？"))
            assertTrue(answer.text.isNotBlank())
            val repository = assertIs<AgentResult.Success>(runner.run("阅读当前目录的 build.gradle.kts，用一句中文说明该项目使用的语言与 Java 版本。不要修改文件。"))
            assertTrue(repository.text.contains("Kotlin", ignoreCase = true))
            assertTrue(repository.text.contains("11"))
        }
    }
}
