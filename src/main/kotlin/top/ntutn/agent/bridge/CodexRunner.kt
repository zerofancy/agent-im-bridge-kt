package top.ntutn.agent.bridge

import java.nio.file.Path

/** Compatibility entry point for callers using the Codex backend. */
class CodexRunner(binary: String, codexHome: Path = defaultCodexHome()) :
    AppServerAgentRunner(BackendSpec(BackendId.CODEX, binary, codexHome)) {
    companion object {
        fun defaultCodexHome(): Path = canonicalDirectory(Path.of(System.getenv("CODEX_HOME")?.takeIf { it.isNotBlank() }
            ?: Path.of(System.getProperty("user.home"), ".codex").toString()))
    }

}
