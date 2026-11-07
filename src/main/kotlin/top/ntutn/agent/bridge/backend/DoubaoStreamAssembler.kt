package top.ntutn.agent.bridge.backend

/**
 * Splits Doubao CLI terminal output into tool-status lines and model answer text.
 *
 * The CLI renders execution status as its own bullet lines ("· 正在执行代码"、"· 已运行 …")
 * ahead of the final answer; model prose uses normal Markdown bullets, so the "· " prefix
 * identifies CLI status lines across tool types without coupling to a fixed verb list.
 * The process buffer keeps a bounded tail so long runs cannot grow the card without limit.
 */
internal class DoubaoStreamAssembler {
    private val answerBuilder = StringBuilder()
    private val processBuilder = StringBuilder()

    val answer: String
        get() = answerBuilder.toString()

    val process: String
        get() = processBuilder.toString()

    fun append(line: String, terminated: Boolean) {
        if (line.startsWith(TOOL_STATUS_PREFIX)) {
            appendBoundedProcess(line)
        } else {
            answerBuilder.append(line)
            if (!terminated) {
                answerBuilder.append('\n')
            }
        }
    }

    private fun appendBoundedProcess(line: String) {
        processBuilder.append(line).append('\n')
        if (processBuilder.length > MAX_PROCESS_CHARS) {
            val overflow = processBuilder.length - MAX_PROCESS_CHARS
            val boundary = processBuilder.indexOf("\n", overflow)
            processBuilder.delete(0, if (boundary < 0) overflow else boundary + 1)
        }
    }

    companion object {
        const val TOOL_STATUS_PREFIX = "· "
        private const val MAX_PROCESS_CHARS = 6000
    }
}
