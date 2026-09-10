package top.ntutn.agent.bridge

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class RuntimeEnvironmentTest {
    @TempDir lateinit var temp: Path
    @Test fun `uncreated child of symlink resolves to actual directory`() {
        val real = Files.createDirectory(temp.resolve("real"))
        val link = Files.createSymbolicLink(temp.resolve("link"), real)
        assertEquals(real.toRealPath().resolve("future/child"), realRuntimePath(link.resolve("future/child")))
    }
    @Test fun `environment never inherits ambient backend roots and rejects overlap`() {
        val env = RuntimeEnvironment(temp, "dev", "version")
        val work = Files.createDirectory(temp.resolve("dev-work"))
        JsonFiles.write(env.directory.resolve("runtime.json"), json("workspace" to work.toString()))
        val config = BridgeConfig("dev-app", "secret", "ou_owner", "feishu")
        env.validate(config)
        val backend = env.backend(BackendId.CODEX, env.options())
        assertEquals(env.directory.resolve("backend/codex"), backend.runtimeRoot)
        assertEquals(env.temporary.toString(), backend.environment["TMPDIR"])
        val peer = temp.resolve("environments/prod")
        JsonFiles.write(peer.resolve("runtime.json"), json("workspace" to work.toString()))
        JsonFiles.write(peer.resolve("config.json"), json("appId" to "prod-app"))
        assertFailsWith<IllegalArgumentException> { env.validate(config) }
    }
    @Test fun `same bot rejected and mutable classpath cannot start as a release`() {
        val env = RuntimeEnvironment(temp, "dev", "version")
        JsonFiles.write(env.directory.resolve("runtime.json"), json("workspace" to temp.toString()))
        JsonFiles.write(temp.resolve("environments/prod/config.json"), json("appId" to "same"))
        assertFailsWith<IllegalArgumentException> { env.validate(BridgeConfig("same", "secret", "ou_owner", "feishu")) }
        Files.createDirectories(env.releaseDirectory.resolve("lib"))
        assertFailsWith<IllegalArgumentException> { env.verifyClasspath() }
    }
}
