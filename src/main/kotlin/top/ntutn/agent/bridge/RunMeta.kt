package top.ntutn.agent.bridge

/** Where the model / effort shown on the result card came from. */
enum class RunMetaSource {
    THREAD,
    CONFIG,
    LAUNCH_FLAG,
    UNKNOWN,
}

/**
 * Model and reasoning effort actually used by one turn.
 *
 * [backend] is filled by the delivery layer, which always knows the backend that ran the turn;
 * runners only report what they can observe.
 */
data class RunMeta(
    val backend: String? = null,
    val model: String? = null,
    val effort: String? = null,
    val effortDefault: Boolean = false,
    val effortFixed: Boolean = false,
    val source: RunMetaSource = RunMetaSource.UNKNOWN,
) {
    fun withBackend(name: String): RunMeta = copy(backend = name)

    /**
     * Fill in what a catalog entry knows: an omitted effort means "model default", and a model
     * without adjustable levels is fixed.
     */
    fun withCatalog(defaultEffort: String?, fixed: Boolean): RunMeta =
        copy(
            effort = effort ?: defaultEffort,
            effortDefault = effort == null && defaultEffort != null,
            effortFixed = fixed,
        )

    /** Grey meta line on the result card: backend first, then model, then effort. */
    fun displayLine(): String =
        buildList {
                backend?.takeIf { it.isNotBlank() }?.let { add("后端 $it") }
                model?.takeIf { it.isNotBlank() }?.let { add("模型 $it") }
                effort?.takeIf { it.isNotBlank() }?.let { value ->
                    val suffix =
                        when {
                            effortFixed -> "（固定）"
                            effortDefault -> "（默认）"
                            else -> ""
                        }
                    add("强度 $value$suffix")
                }
            }
            .joinToString("　｜　")
}
