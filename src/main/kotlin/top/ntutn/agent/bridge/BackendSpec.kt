package top.ntutn.agent.bridge

import java.nio.file.Path
import top.ntutn.agent.bridge.backend.RpcFailure

enum class BackendId(val configValue: String, val displayName: String) {
    CODEX("codex", "Codex"), TRAEX("traex", "Traex"), OPENCODE("opencode", "OpenCode");

    companion object {
        fun parse(value: String): BackendId = entries.firstOrNull { it.configValue == value }
            ?: throw IllegalArgumentException("backend 必须为 codex、traex 或 opencode。")
    }
}

data class BackendSpec(val id: BackendId, val binary: String, val runtimeRoot: Path,
                       val sharedRoot: Path = runtimeRoot, val temporaryRoot: Path? = null) {
    val displayName: String get() = id.displayName
    val environment: Map<String, String> get() = (when (id) {
        BackendId.OPENCODE -> mapOf("XDG_DATA_HOME" to runtimeRoot.resolve("data").toString(),
            "XDG_CONFIG_HOME" to runtimeRoot.resolve("config").toString(),
            "XDG_CACHE_HOME" to runtimeRoot.resolve("cache").toString(),
            "XDG_STATE_HOME" to runtimeRoot.resolve("state").toString())
        BackendId.CODEX -> mapOf("CODEX_HOME" to runtimeRoot.toString())
        BackendId.TRAEX -> mapOf("TRAE_HOME" to sharedRoot.toString(), "TRAECLI_HOME" to runtimeRoot.toString())
    }) + (temporaryRoot?.let { mapOf("TMPDIR" to it.toString(), "TMP" to it.toString(), "TEMP" to it.toString()) } ?: emptyMap())
    val excludeTurns: Boolean get() = id == BackendId.CODEX

    internal fun isMissingSession(error: RpcFailure, sessionId: String): Boolean =
        error.code == -32600 && error.diagnostic == "no rollout found for thread id $sessionId"

    // Verified against traex 0.204.1: other -32600 errors must never trigger a retry.
    internal fun isInactiveTurn(error: RpcFailure): Boolean = id == BackendId.TRAEX &&
        error.code == -32600 && error.diagnostic == "no active turn to interrupt"

    companion object {
        fun resolve(id: BackendId, options: RunOptions, environment: Map<String, String> = System.getenv(),
                    userHome: Path = Path.of(System.getProperty("user.home"))): BackendSpec {
            fun root(name: String, fallback: Path) = canonicalDirectory(
                environment[name]?.takeIf { it.isNotBlank() }?.let { Path.of(it) } ?: fallback)
            return when (id) {
                BackendId.OPENCODE -> BackendSpec(id, options.opencodeBinary, root("BRIDGE_OPENCODE_HOME", userHome.resolve(".agent-im-bridge-kt/backend/opencode")))
                BackendId.CODEX -> BackendSpec(id, options.binary, root("CODEX_HOME", userHome.resolve(".codex")))
                BackendId.TRAEX -> {
                    val shared = root("TRAE_HOME", userHome.resolve(".trae"))
                    BackendSpec(id, options.traexBinary, root("TRAECLI_HOME", shared.resolve("cli")), shared)
                }
            }
        }
    }
}
