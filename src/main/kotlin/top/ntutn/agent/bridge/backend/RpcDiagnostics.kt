package top.ntutn.agent.bridge.backend

/** Only fixed labels leave the RPC boundary; arbitrary server text stays in memory. */
internal fun RpcFailure.safeReason(): String = when {
    diagnostic == "unsupported excludeTurns" -> "unsupported_exclude_turns"
    diagnostic == "no active turn to interrupt" -> "no_active_turn"
    diagnostic?.matches(Regex("no rollout found for thread id [0-9a-fA-F-]{36}")) == true -> "session_missing"
    diagnostic == "permission denied" -> "permission_denied"
    code == -32700 -> "parse_error"
    code == -32600 -> "invalid_request"
    code == -32601 -> "method_not_found"
    code == -32602 -> "invalid_params"
    code == -32603 -> "internal_error"
    else -> "unclassified"
}
