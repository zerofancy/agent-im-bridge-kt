package top.ntutn.agent.bridge.feishu.cot

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.ntutn.agent.bridge.*

internal data class CotRecord(
    val requestId: String,
    val state: String,
    val cotId: String? = null,
    val messageId: String? = null,
    val sequence: Int = 0,
    val outcome: String = "unknown",
    val threadId: String = "",
    val runId: String = "",
) {
    val reference: CotReference?
        get() =
            if (cotId != null && messageId != null) {
                CotReference(cotId, messageId)
            } else {
                null
            }
}

internal class CotRunStore(private val directory: Path) {
    private val mutex = Mutex()

    init {
        require(directory.isAbsolute)
        PlatformFiles.createDirectories(directory)
    }

    private fun path(id: String): Path =
        directory.resolve(
            MessageDigest.getInstance("SHA-256").digest(id.toByteArray()).joinToString("") {
                "%02x".format(it)
            } + ".json"
        )

    suspend fun save(record: CotRecord) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                withContext(NonCancellable) {
                    JsonFiles.write(
                        path(record.requestId),
                        json(
                            "requestId" to record.requestId,
                            "state" to record.state,
                            "cotId" to record.cotId,
                            "messageId" to record.messageId,
                            "sequence" to record.sequence,
                            "outcome" to record.outcome,
                            "threadId" to record.threadId,
                            "runId" to record.runId,
                        ),
                    )
                }
            }
        }

    suspend fun claim(record: CotRecord): Boolean =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val target = path(record.requestId)
                if (Files.exists(target, NOFOLLOW_LINKS)) {
                    return@withLock false
                }
                withContext(NonCancellable) {
                    JsonFiles.write(
                        target,
                        json("requestId" to record.requestId, "state" to "creating"),
                    )
                }
                true
            }
        }

    suspend fun records(): List<CotRecord> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                Files.newDirectoryStream(directory, "*.json").use { entries ->
                    entries
                        .filter { Files.isRegularFile(it, NOFOLLOW_LINKS) }
                        .map { file ->
                            val value =
                                JsonFiles.read(file)
                                    ?: throw java.io.IOException("Missing COT record")
                            CotRecord(
                                value.string("requestId")
                                    ?: throw java.io.IOException("Invalid COT record"),
                                value.string("state")
                                    ?: throw java.io.IOException("Invalid COT state"),
                                value.string("cotId"),
                                value.string("messageId"),
                                value["sequence"]?.asInt ?: 0,
                                value.string("outcome") ?: "unknown",
                                value.string("threadId").orEmpty(),
                                value.string("runId").orEmpty(),
                            )
                        }
                        .toList()
                }
            }
        }
}
