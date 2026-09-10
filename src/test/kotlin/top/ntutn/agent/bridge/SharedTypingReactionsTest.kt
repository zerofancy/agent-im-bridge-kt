package top.ntutn.agent.bridge

import kotlinx.coroutines.*
import kotlin.test.*

class SharedTypingReactionsTest {
    private val route = ReplyRoute("chat", "om_bot")
    @Test fun `overlapping requests share reaction until last release`(): Unit = runBlocking {
        var adds = 0
        val removes = mutableListOf<String?>()
        val shared = SharedTypingReactions(object : TypingReactions {
            override suspend fun add(route: ReplyRoute): String { adds++; return "typing" }
            override suspend fun remove(route: ReplyRoute, reactionId: String?) { removes += reactionId }
        })
        val first = shared.forRequest(); val second = shared.forRequest()
        assertEquals("typing", first.add(route))
        assertEquals("typing", second.add(route))
        first.remove(route, "typing")
        assertTrue(removes.isEmpty())
        second.remove(route, "typing")
        assertEquals(1, adds)
        assertEquals(listOf<String?>("typing"), removes)
    }

    @Test fun `new request waits for prior removal and cancelled waiter cannot remove it`(): Unit = runBlocking {
        val removing = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var adds = 0; var removes = 0
        val shared = SharedTypingReactions(object : TypingReactions {
            override suspend fun add(route: ReplyRoute): String = "typing-${++adds}"
            override suspend fun remove(route: ReplyRoute, reactionId: String?) { removes++; removing.complete(Unit); release.await() }
        })
        val first = shared.forRequest(); first.add(route)
        val cleanup = launch { first.remove(route, "typing-1") }
        removing.await()
        val cancelled = shared.forRequest()
        val waiting = launch(start = CoroutineStart.UNDISPATCHED) { cancelled.add(route) }
        waiting.cancelAndJoin(); cancelled.remove(route, null)
        val next = shared.forRequest()
        val added = async(start = CoroutineStart.UNDISPATCHED) { next.add(route) }
        assertEquals(1, adds)
        release.complete(Unit); cleanup.join()
        assertEquals("typing-2", added.await())
        next.remove(route, "typing-2")
        assertEquals(2, removes)
    }

    @Test fun `failed creation retains unknown id cleanup and releases registry`(): Unit = runBlocking {
        var attempts = 0
        val removals = mutableListOf<String?>()
        val shared = SharedTypingReactions(object : TypingReactions {
            override suspend fun add(route: ReplyRoute): String { if (++attempts == 1) error("failed"); return "next" }
            override suspend fun remove(route: ReplyRoute, reactionId: String?) { removals += reactionId }
        })
        val first = shared.forRequest()
        assertFailsWith<IllegalStateException> { first.add(route) }
        first.remove(route, null)
        val next = shared.forRequest(); assertEquals("next", next.add(route)); next.remove(route, "next")
        assertEquals(listOf(null, "next"), removals)
    }
}
