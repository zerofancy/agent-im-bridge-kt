package top.ntutn.agent.bridge.feishu.cot

import com.google.gson.JsonObject
import java.io.IOException
import java.nio.file.Path
import kotlin.test.*
import kotlinx.coroutines.*
import org.junit.jupiter.api.io.TempDir
import top.ntutn.agent.bridge.*

internal class FakeCotApi : CotApi {
    var creates = 0
    var createFailure: IOException? = null
    var appendFailure = false
    var completeFailure = false
    val events = mutableListOf<JsonObject>()
    val reasons = mutableListOf<String>()
    val created = CompletableDeferred<Unit>()

    override suspend fun create(route: ReplyRoute): CotReference {
        creates++
        created.complete(Unit)
        createFailure?.let { throw it }
        return CotReference("cot", "om-cot")
    }

    override suspend fun append(ref: CotReference, events: List<JsonObject>) {
        this.events += events
        if (appendFailure) {
            throw IOException("fixture unknown")
        }
    }

    override suspend fun complete(ref: CotReference, reason: String) {
        reasons += reason
        if (completeFailure) {
            throw IOException("fixture unknown")
        }
    }
}

class CotReporterTest {
    @TempDir lateinit var temp: Path
    private val route = ReplyRoute("oc", "om")

    @Test
    fun `normal run has paired terminal and durable close`() = runBlocking {
        val api = FakeCotApi()
        val store = CotRunStore(temp)
        val reporter =
            CotProgressReporter(
                this,
                CotRuntime(api, store),
                route,
                "request",
                { fail("unexpected fallback") },
                1,
            )
        reporter.offer(
            AgentRunEvent(AgentRunEvent.Kind.START, threadId = "thread", turnId = "turn")
        )
        reporter.offer(AgentRunEvent(AgentRunEvent.Kind.TOOL_START, "tool", "执行命令"))
        reporter.offer(AgentRunEvent(AgentRunEvent.Kind.TOOL_END, "tool", "执行命令", failed = true))
        reporter.finished(AgentResult.Success("final", "thread"))
        reporter.close()
        assertEquals(listOf("done"), api.reasons)
        assertEquals(1, api.events.count { it.string("event_type") == "RUN_STARTED" })
        assertEquals(1, api.events.count { it.string("event_type") == "RUN_FINISHED" })
        assertTrue(api.events.toString().contains("工具执行失败"))
        assertFalse(api.events.toString().contains("final"))
        assertEquals("closed", store.records().single().state)
    }

    @Test
    fun `only definite create rejection falls back and neither outcome retries`() = runBlocking {
        for ((index, failure) in listOf(CotRejected(), IOException("unknown")).withIndex()) {
            val api = FakeCotApi().apply { createFailure = failure }
            var fallbacks = 0
            val reporter =
                CotProgressReporter(
                    this,
                    CotRuntime(api, CotRunStore(temp.resolve("case-$index"))),
                    route,
                    "request",
                    { fallbacks++ },
                    1,
                )
            reporter.finished(AgentResult.Success("final"))
            reporter.close()
            assertEquals(1, api.creates)
            assertEquals(
                if (failure is CotRejected) {
                    1
                } else {
                    0
                },
                fallbacks,
            )
        }
    }

    @Test
    fun `update unknown never replays batch but still completes`() = runBlocking {
        val api = FakeCotApi().apply { appendFailure = true }
        val reporter =
            CotProgressReporter(this, CotRuntime(api, CotRunStore(temp)), route, "request", {}, 1)
        reporter.offer(AgentRunEvent(AgentRunEvent.Kind.START, threadId = "t", turnId = "r"))
        reporter.offer(AgentRunEvent(AgentRunEvent.Kind.TEXT, "text", "first"))
        reporter.finished(AgentResult.Success("result"))
        reporter.close()
        assertEquals(1, api.events.count { it.string("event_type") == "RUN_STARTED" })
        assertEquals(1, api.events.count { it.string("event_type") == "TEXT_MESSAGE_CONTENT" })
        assertEquals(listOf("done"), api.reasons)
    }

    @Test
    fun `confirmed stop closes once with interrupted rather than success`() = runBlocking {
        val api = FakeCotApi()
        val reporter =
            CotProgressReporter(this, CotRuntime(api, CotRunStore(temp)), route, "request", {}, 1)
        reporter.offer(AgentRunEvent(AgentRunEvent.Kind.START, threadId = "t", turnId = "r"))
        reporter.finished(AgentResult.Failure(AgentResult.Kind.STOPPED))
        reporter.finished(AgentResult.Success("late"))
        reporter.close()
        assertEquals(listOf("error"), api.reasons)
        assertTrue(api.events.toString().contains("interrupted"))
    }

    @Test
    fun `overflow is bounded and terminal still delivered`() = runBlocking {
        val api = FakeCotApi()
        val runtime = CotRuntime(api, CotRunStore(temp))
        val reporter = CotProgressReporter(this, runtime, route, "request", {}, 1)
        reporter.offer(AgentRunEvent(AgentRunEvent.Kind.START, threadId = "t", turnId = "r"))
        repeat(10_000) {
            reporter.offer(AgentRunEvent(AgentRunEvent.Kind.TEXT, "text-$it", "x".repeat(1000)))
        }
        reporter.finished(AgentResult.Success("final"))
        reporter.close()
        assertEquals("queue_overflow", runtime.failure.value)
        assertEquals(1, api.events.count { it.string("event_type") == "RUN_FINISHED" })
        assertTrue(api.events.size < 50)
    }

    @Test
    fun `restart never repeats uncertain close or unknown create`() = runBlocking {
        val store = CotRunStore(temp)
        store.save(CotRecord("unknown-create", "creating"))
        store.save(CotRecord("unknown-close", "close_attempted", "cot-1", "om-1"))
        store.save(CotRecord("active", "active", "cot-2", "om-2"))
        val api = FakeCotApi()
        val runtime = CotRuntime(api, store)
        runtime.recover()
        runtime.recover()
        assertEquals(0, api.creates)
        assertEquals(listOf("error"), api.reasons)
        assertEquals("closed", store.records().first { it.requestId == "active" }.state)
    }

    @Test
    fun `duplicate request claims create once and complete unknown survives restart`() =
        runBlocking {
            val store = CotRunStore(temp)
            val api = FakeCotApi().apply { completeFailure = true }
            val runtime = CotRuntime(api, store)
            repeat(2) {
                val reporter = CotProgressReporter(this, runtime, route, "same-request", {}, 1)
                reporter.finished(AgentResult.Success("result"))
                reporter.close()
            }
            CotRuntime(api, store).recover()
            assertEquals(1, api.creates)
            assertEquals(listOf("done"), api.reasons)
            assertEquals("close_attempted", store.records().single().state)
        }
}
