package top.ntutn.agent.bridge.backend

import com.google.gson.JsonObject
import java.io.IOException
import java.util.Locale
import top.ntutn.agent.bridge.string

/** Only fixed classifications survive the RPC boundary; server text is never retained. */
internal class DeepSeekRpcFailure(
    val code: Int?,
    val reason: Reason,
    val method: String,
    val rpcId: Long,
    val envelope: Envelope,
    val category: Category,
) :
    IOException(
        "DeepSeek ACP rejected request method=$method rpcId=$rpcId code=$code envelope=$envelope category=$category"
    ) {
    enum class Reason {
        MISSING,
        OCCUPIED,
        DIRECTORY,
        INVALID,
        EXTERNAL,
        UNKNOWN,
    }

    enum class Envelope {
        TURN_FAILED,
        ADMISSION_FAILED,
        OUTPUT_FAILED,
        SETTLEMENT_FAILED,
        CLOSE_FAILED,
        OTHER,
    }

    enum class Category {
        AUTH,
        RATE_LIMIT,
        QUOTA,
        NETWORK,
        SERVER,
        SESSION_MISSING,
        SESSION_BUSY,
        DIRECTORY,
        INVALID,
        UNKNOWN,
    }

    /** The fixed upstream turn-failure envelope is emitted after the admitted turn settles. */
    val admittedTurnEnded: Boolean
        get() = method == "session/prompt" && code == -32603 && envelope == Envelope.TURN_FAILED
}

internal fun deepSeekRpcFailure(
    error: JsonObject,
    method: String,
    rpcId: Long,
): DeepSeekRpcFailure {
    val code = error["code"]?.takeIf { it.isJsonPrimitive }?.asString?.toIntOrNull()
    val message = error.string("message").orEmpty()
    val detail = message.removePrefix("Invalid params: ")
    val envelope =
        when {
            method == "session/prompt" && message.startsWith(TURN_PREFIX) -> {
                DeepSeekRpcFailure.Envelope.TURN_FAILED
            }
            method == "session/prompt" &&
                message.startsWith("Internal error: prompt was not queued: ") -> {
                DeepSeekRpcFailure.Envelope.ADMISSION_FAILED
            }
            method == "session/prompt" &&
                message.startsWith("Internal error: assistant output delivery failed: ") -> {
                DeepSeekRpcFailure.Envelope.OUTPUT_FAILED
            }
            method == "session/prompt" &&
                message.startsWith("Internal error: prompt settlement failed: ") -> {
                DeepSeekRpcFailure.Envelope.SETTLEMENT_FAILED
            }
            method == "session/close" &&
                message.startsWith("Internal error: session close failed: ") -> {
                DeepSeekRpcFailure.Envelope.CLOSE_FAILED
            }
            else -> {
                DeepSeekRpcFailure.Envelope.OTHER
            }
        }
    val category =
        when {
            code == -32602 &&
                method == "session/resume" &&
                detail.startsWith("session is not resumable: ") -> {
                DeepSeekRpcFailure.Category.SESSION_MISSING
            }
            code == -32602 &&
                method == "session/resume" &&
                detail.startsWith("session is already active: ") -> {
                DeepSeekRpcFailure.Category.SESSION_BUSY
            }
            code == -32602 &&
                method == "session/resume" &&
                detail.startsWith("session cwd does not match: ") -> {
                DeepSeekRpcFailure.Category.DIRECTORY
            }
            code == -32602 -> {
                DeepSeekRpcFailure.Category.INVALID
            }
            code == -32000 -> {
                DeepSeekRpcFailure.Category.AUTH
            }
            code == -32603 && envelope == DeepSeekRpcFailure.Envelope.TURN_FAILED -> {
                externalTurnFailure(message.removePrefix(TURN_PREFIX))
            }
            else -> {
                DeepSeekRpcFailure.Category.UNKNOWN
            }
        }
    val reason =
        when (category) {
            DeepSeekRpcFailure.Category.SESSION_MISSING -> {
                DeepSeekRpcFailure.Reason.MISSING
            }
            DeepSeekRpcFailure.Category.SESSION_BUSY -> {
                DeepSeekRpcFailure.Reason.OCCUPIED
            }
            DeepSeekRpcFailure.Category.DIRECTORY -> {
                DeepSeekRpcFailure.Reason.DIRECTORY
            }
            DeepSeekRpcFailure.Category.INVALID -> {
                DeepSeekRpcFailure.Reason.INVALID
            }
            DeepSeekRpcFailure.Category.UNKNOWN -> {
                DeepSeekRpcFailure.Reason.UNKNOWN
            }
            else -> {
                DeepSeekRpcFailure.Reason.EXTERNAL
            }
        }
    return DeepSeekRpcFailure(code, reason, method, rpcId, envelope, category)
}

private const val TURN_PREFIX = "Internal error: turn failed: "

private fun externalTurnFailure(message: String): DeepSeekRpcFailure.Category {
    val detail = message.lowercase(Locale.ROOT)
    // Policy and programming faults remain fatal even if their text mentions auth or networking.
    if (
        Regex(
                "bridge_deepseek_|\\b(?:typeerror|referenceerror|assertion|invariant|sandbox|policy)\\b|is not a function|cannot read propert"
            )
            .containsMatchIn(detail)
    ) {
        return DeepSeekRpcFailure.Category.UNKNOWN
    }
    val status =
        Regex(
                "(?:\\bhttp[ :_]+|\\bstatus(?: code)?[ :=]+|deepseek messages request failed \\()(401|402|403|408|413|429|5[0-9]{2})\\b"
            )
            .find(detail)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()
    val tokenRejected =
        Regex(
                "\\b(?:den(?:y|ied|ying)|reject(?:ed|ion)?|unauthori[sz]ed|forbidden|not authori[sz]ed|not permitted)\\b"
            )
            .containsMatchIn(detail) ||
            Regex("\\binvalid\\b.{0,160}\\btoken\\b").containsMatchIn(detail)
    return when {
        status in setOf(401, 403) ||
            (detail.contains("oauth") && detail.contains("token") && tokenRejected) ||
            Regex(
                    "\\b(?:authentication_unavailable|authentication_error|invalid_api_key|invalid_token)\\b"
                )
                .containsMatchIn(detail) ||
            detail.startsWith("llm-deepseek: no api key for provider route ") -> {
            DeepSeekRpcFailure.Category.AUTH
        }
        status == 429 ||
            Regex("\\brate[_ -]limit(?:_error| exceeded| reached)?\\b|\\btoo many requests\\b")
                .containsMatchIn(detail) -> {
            DeepSeekRpcFailure.Category.RATE_LIMIT
        }
        status == 402 ||
            Regex(
                    "\\binsufficient[_ ](?:balance|quota)\\b|\\bquota exceeded\\b|\\bcredit balance is too low\\b|\\bexceeded your current quota\\b"
                )
                .containsMatchIn(detail) -> {
            DeepSeekRpcFailure.Category.QUOTA
        }
        status == 408 ||
            detail == "deepseek messages transport failed" ||
            detail == "deepseek messages stream idle timeout" ||
            Regex("\\b(?:econnreset|etimedout|eai_again|econnrefused|enotfound)\\b")
                .containsMatchIn(detail) -> {
            DeepSeekRpcFailure.Category.NETWORK
        }
        status != null && status >= 500 -> {
            DeepSeekRpcFailure.Category.SERVER
        }
        else -> {
            DeepSeekRpcFailure.Category.UNKNOWN
        }
    }
}
