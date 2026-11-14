package top.ntutn.agent.bridge.backend

/** Retains fixed diagnostic vocabulary only, never an external stderr line or payload. */
internal class DeepSeekDiagnostics {
    @Volatile
    var bytes: Long = 0
        private set

    @Volatile
    var category = "UNKNOWN"
        private set

    private var suffix = ""

    fun accept(buffer: ByteArray, count: Int) {
        bytes += count
        val ascii =
            buildString(count) {
                for (index in 0 until count) {
                    val value = buffer[index].toInt() and 255
                    append(
                        if (value in 32..126) {
                            value.toChar()
                        } else {
                            ' '
                        }
                    )
                }
            }
        val window = suffix + ascii
        for (code in codes) {
            if (window.contains(code)) {
                category = code
            }
        }
        suffix = window.takeLast(96)
    }

    private companion object {
        val codes =
            setOf(
                "BRIDGE_DEEPSEEK_MANIFEST_UNREADABLE",
                "BRIDGE_DEEPSEEK_RUNTIME_LINK_ESCAPE",
                "BRIDGE_DEEPSEEK_UNSUPPORTED_RUNTIME",
                "BRIDGE_DEEPSEEK_RUNTIME_INTEGRITY_FAILED",
                "BRIDGE_DEEPSEEK_POLICY_INTEGRITY_FAILED",
                "BRIDGE_DEEPSEEK_CLI_VERSION_MISMATCH",
                "BRIDGE_DEEPSEEK_NODE_REQUIRED",
                "BRIDGE_DEEPSEEK_NODE_UNAVAILABLE",
                "BRIDGE_DEEPSEEK_NODE_TOO_OLD",
                "BRIDGE_DEEPSEEK_CUSTOM_PROFILE_REJECTED",
                "BRIDGE_DEEPSEEK_EXPLICIT_PATH_REQUIRED",
                "BRIDGE_DEEPSEEK_INVALID_SANDBOX_MODE",
                "BRIDGE_DEEPSEEK_INVALID_MODEL_ROUTE",
                "BRIDGE_DEEPSEEK_LAUNCH_DOTENV_REJECTED",
                "BRIDGE_DEEPSEEK_AUTH_CONFIGURATION_INVALID",
                "BRIDGE_DEEPSEEK_AUTH_STATUS_INVALID",
                "BRIDGE_DEEPSEEK_UNSUPPORTED_ARGUMENTS",
                "BRIDGE_DEEPSEEK_LAUNCH_FAILED",
                "BRIDGE_DEEPSEEK_SESSION_POLICY_UNAVAILABLE",
                "BRIDGE_DEEPSEEK_SESSION_POLICY_MISMATCH",
                "BRIDGE_DEEPSEEK_SANDBOX_PROBE_START_FAILED",
                "BRIDGE_DEEPSEEK_SANDBOX_PROBE_FAILED",
                "BRIDGE_DEEPSEEK_SANDBOX_PROBE_INVALID_RESULT",
                "BRIDGE_DEEPSEEK_PARTIAL_SANDBOX_UNSUPPORTED",
                "BRIDGE_DEEPSEEK_SANDBOX_BOUNDARY_FAILED",
            )
    }
}
