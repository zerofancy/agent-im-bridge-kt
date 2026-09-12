package top.ntutn.agent.bridge.storage

import top.ntutn.agent.bridge.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.nio.file.Files
import java.util.UUID
import kotlin.test.*
import top.ntutn.agent.bridge.backend.CodexRunner

@EnabledIfEnvironmentVariable(named = "CODEX_LIVE_TEST", matches = "1")
class AttachmentLiveTest {
    @Test fun `readonly model can read a downloaded attachment outside cwd`(): Unit = runBlocking {
        val root = Files.createTempDirectory("bridge-attachment-live-")
        val workspace = Files.createDirectory(root.resolve("workspace"))
        val lease = AttachmentStore(root.resolve("attachments")).acquire()
        try {
            val secret = UUID.randomUUID().toString()
            val file = lease.save(secret.toByteArray(), "attachment.txt")
            CodexRunner("codex").use { runner ->
                val answer = assertIs<AgentResult.Success>(runner.run(
                    "Read the local attachment at $file using a tool. Reply with its exact contents only. Do not modify files.",
                    workspace = workspace, sandboxMode = SandboxMode.READ_ONLY))
                assertContains(answer.text, secret)
                assertEquals(secret, Files.readString(file))
            }
        } finally {
            lease.release()
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }
}
