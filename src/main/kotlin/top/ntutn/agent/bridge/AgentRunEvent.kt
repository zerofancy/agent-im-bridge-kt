package top.ntutn.agent.bridge

data class AgentRunEvent(
    val kind: Kind,
    val itemId: String = "",
    val text: String = "",
    val failed: Boolean = false,
    val threadId: String = "",
    val turnId: String = "",
    val toolName: String = "",
) {
    enum class Kind {
        START,
        TEXT,
        TEXT_END,
        TOOL_START,
        TOOL_END,
        DONE,
        ERROR,
        INTERRUPTED,
        UNKNOWN,
        STATUS,
    }
}

internal class RunEventTracker(private val emit: (AgentRunEvent) -> Unit) {
    private val texts = mutableMapOf<String, String>()
    private val starts = mutableSetOf<String>()
    private val ends = mutableSetOf<String>()

    fun text(id: String, value: String, done: Boolean, thread: String, turn: String) {
        if (id !in texts && texts.size >= 512) {
            return
        }
        val previous = texts[id].orEmpty()
        if (value.startsWith(previous) && value.length > previous.length) {
            emit(
                AgentRunEvent(
                    AgentRunEvent.Kind.TEXT,
                    id,
                    value.substring(previous.length),
                    threadId = thread,
                    turnId = turn,
                )
            )
        }
        texts[id] = value
        if (done && ends.add("text/$id")) {
            emit(AgentRunEvent(AgentRunEvent.Kind.TEXT_END, id, threadId = thread, turnId = turn))
        }
    }

    fun tool(
        id: String,
        label: String,
        done: Boolean,
        failed: Boolean,
        thread: String,
        turn: String,
        toolName: String = "",
    ) {
        if (id !in starts && starts.size >= 512) {
            return
        }
        if (starts.add(id)) {
            emit(
                AgentRunEvent(
                    AgentRunEvent.Kind.TOOL_START,
                    id,
                    label,
                    threadId = thread,
                    turnId = turn,
                    toolName = toolName,
                )
            )
        }
        if (done && ends.add("tool/$id")) {
            emit(
                AgentRunEvent(
                    AgentRunEvent.Kind.TOOL_END,
                    id,
                    label,
                    failed,
                    thread,
                    turn,
                    toolName,
                )
            )
        }
    }
}
