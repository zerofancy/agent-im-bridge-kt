package top.ntutn.agent.bridge

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.test.*

@EnabledIfEnvironmentVariable(named = "CODEX_LIVE_TEST", matches = "1")
class SandboxLiveTest {
    @Test fun `new and resumed thread obey current sandbox mode`() {
        // Outside the OS temporary directories, which workspace-write may separately allow.
        val root = Files.createTempDirectory(Path.of(System.getProperty("user.home")), ".bridge-sandbox-test-")
        val workspace = Files.createDirectory(root.resolve("workspace"))
        val outside = Files.createDirectory(root.resolve("outside"))
        try {
            CodexRunner("codex", Duration.ofSeconds(120)).use { runner ->
                var id: String? = null
                for (mode in SandboxMode.entries) {
                    val insideFile = workspace.resolve(mode.cliValue + ".txt")
                    val outsideFile = outside.resolve(mode.cliValue + ".txt")
                    val prompt = """
                        This is an authorized sandbox integration test using two disposable test files.
                        Run a shell command to attempt BOTH writes independently: write the text probe to
                        $insideFile and $outsideFile.
                        Attempt both even if the first is denied. Do not request escalation or change permissions.
                        Do not modify any other files. Report which writes succeeded or were denied.
                    """.trimIndent()
                    val result = assertIs<AgentResult.Success>(runner.run(prompt, id, workspace, mode))
                    if (id != null) assertEquals(id, result.sessionId)
                    id = assertNotNull(result.sessionId)
                    assertEquals(mode != SandboxMode.READ_ONLY, Files.exists(insideFile), "inside: $mode")
                    assertEquals(mode == SandboxMode.FULL_ACCESS, Files.exists(outsideFile), "outside: $mode")
                }
            }
        } finally {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }
}
