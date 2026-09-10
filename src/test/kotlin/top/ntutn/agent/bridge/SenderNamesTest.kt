package top.ntutn.agent.bridge

import kotlinx.coroutines.*
import kotlin.test.*

class SenderNamesTest {
    @Test fun `success and failure are cached and missing sender never queries`(): Unit = runBlocking {
        val calls = mutableListOf<Pair<String, String>>()
        val cache = SenderNameCache("app", this, SenderNameSource { id, type ->
            calls += id to type
            when (id) {
                "good" -> "张三"
                "blank" -> " \n\t"
                "timeout" -> withTimeout(1) { awaitCancellation() }
                "denied" -> error("external details")
                else -> null
            }
        })
        repeat(2) {
            assertEquals("张三", cache.name(MessageSender("good")))
            for (id in listOf("blank", "denied", "empty", "timeout")) assertEquals(id, cache.name(MessageSender(id)))
            assertEquals("未知发送人", cache.name(MessageSender()))
            assertEquals("未知发送人", cache.name(MessageSender(" ")))
        }
        assertEquals(listOf("good", "blank", "denied", "empty", "timeout"), calls.map { it.first })
        assertEquals("张三", cache.name(MessageSender("good", idType = "user_id")))
        assertEquals("user_id", calls.last().second)
    }

    @Test fun `LRU capacity refresh eviction and restart include failure entries`(): Unit = runBlocking {
        val calls = mutableListOf<String>()
        val source = SenderNameSource { id, _ -> calls += id; null }
        val cache = SenderNameCache("app", this, source)
        for (i in 1..100) cache.name(MessageSender("$i"))
        cache.name(MessageSender("1"))
        cache.name(MessageSender("101"))
        cache.name(MessageSender("1"))
        assertEquals(101, calls.size)
        cache.name(MessageSender("2"))
        assertEquals(102, calls.size)
        assertEquals("2", calls.last())
        SenderNameCache("app", this, source).name(MessageSender("1"))
        assertEquals(103, calls.size)
    }

    @Test fun `bots use supplied name cache fallback and never call contacts`(): Unit = runBlocking {
        var calls = 0
        val cache = SenderNameCache("app", this, SenderNameSource { _, _ -> calls++; "人名" })
        assertEquals("助手", cache.name(MessageSender("same", type = "app", name = "助手")))
        assertEquals("助手", cache.name(MessageSender("same", type = "app", name = "新名字")))
        assertEquals("other", cache.name(MessageSender("other", type = "app")))
        assertEquals("other", cache.name(MessageSender("other", type = "app", name = "后来有名字")))
        assertEquals(0, calls)
        assertEquals("人名", cache.name(MessageSender("same")))
        assertEquals(1, calls)
        assertEquals("机器人·助手", messageMetadata(MessageSender("same", type = "app"), null, cache)
            .substringAfter("【发送人：").substringBefore("】"))
    }

    @Test fun `concurrent requests share lookup and cancelling one waiter preserves the other`(): Unit = runBlocking {
        var calls = 0
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val cache = SenderNameCache("app", this, SenderNameSource { _, _ -> calls++; entered.complete(Unit); release.await(); "张三" })
        val first = async(start = CoroutineStart.UNDISPATCHED) { cache.name(MessageSender("id")) }
        entered.await()
        val second = async(start = CoroutineStart.UNDISPATCHED) { cache.name(MessageSender("id")) }
        first.cancelAndJoin()
        assertTrue(second.isActive)
        release.complete(Unit)
        assertEquals("张三", second.await())
        assertEquals("张三", cache.name(MessageSender("id")))
        assertEquals(1, calls)
    }

    @Test fun `last cancellation waits for cleanup and does not cache fallback`(): Unit = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val cleaning = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        var calls = 0
        val cache = SenderNameCache("app", this, SenderNameSource { _, _ ->
            calls++
            if (calls == 1) {
                entered.complete(Unit)
                try { awaitCancellation() }
                finally { withContext(NonCancellable) { cleaning.complete(Unit); releaseCleanup.await() } }
            }
            "查到姓名"
        })
        val first = async { cache.name(MessageSender("id")) }
        entered.await()
        first.cancel()
        cleaning.await()
        assertFalse(first.isCompleted)
        releaseCleanup.complete(Unit)
        first.join()
        assertEquals("查到姓名", cache.name(MessageSender("id")))
        assertEquals(2, calls)
    }

    @Test fun `different senders query concurrently and service scope cancellation stops lookup`(): Unit = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val entered = ChannelCounter(2)
        val cache = SenderNameCache("app", owner, SenderNameSource { _, _ -> entered.arrive(); awaitCancellation() })
        try {
            val first = async { cache.name(MessageSender("one")) }
            val second = async { cache.name(MessageSender("two")) }
            withTimeout(3000) { entered.await() }
            owner.cancel()
            withTimeout(3000) { first.join(); second.join() }
            assertTrue(first.isCancelled); assertTrue(second.isCancelled)
        } finally { owner.cancel() }
    }

    private class ChannelCounter(private val count: Int) {
        private val channel = kotlinx.coroutines.channels.Channel<Unit>(count)
        suspend fun arrive() { channel.send(Unit) }
        suspend fun await() { repeat(count) { channel.receive() } }
    }

    @Test fun `metadata sanitizes single line name and formats creation milliseconds in Shanghai`(): Unit = runBlocking {
        val cache = SenderNameCache("app", this, SenderNameSource { _, _ -> "张\n三\t\u2028\u2029\u202e【姓名】" })
        val timestamp = java.time.Instant.parse("2026-09-09T07:04:05Z").toEpochMilli().toString()
        assertEquals("【发送人：张 三 姓名】【发送时间：2026-09-09 15:04:05 +08:00】",
            messageMetadata(MessageSender("id"), timestamp, cache))
        for (bad in listOf(null, "", "wrong", "-1", "999999999999999999999999")) assertEquals("未知", messageTime(bad))
        assertEquals("【发送人：未知发送人】【发送时间：未知】", messageMetadata(MessageSender(), null, cache))
        assertEquals("1970-01-01 08:00:00 +08:00", messageTime("0"))
    }
}
