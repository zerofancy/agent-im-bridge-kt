package top.ntutn.agent.bridge

/** One validation rule shared by storage, chat control and new-group initialization. */
internal fun validBackendSessionId(backend: BackendId, id: String): Boolean =
    when (backend) {
        BackendId.CODEX,
        BackendId.TRAEX,
        BackendId.DEEPSEEK -> {
            validSessionId(id)
        }
        BackendId.OPENCODE -> {
            openCodeId(id, "ses")
        }
        BackendId.DOUBAO -> {
            validDoubaoSessionId(id)
        }
    }
