package top.ntutn.agent.bridge.backend

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.io.TempDir
import top.ntutn.agent.bridge.AgentProgress
import top.ntutn.agent.bridge.BackendId
import top.ntutn.agent.bridge.BackendSpec
import top.ntutn.agent.bridge.CardDelivery
import top.ntutn.agent.bridge.CardReference
import top.ntutn.agent.bridge.CardReplies
import top.ntutn.agent.bridge.ChatService
import top.ntutn.agent.bridge.ReplyRoute
import top.ntutn.agent.bridge.ReplySender
import top.ntutn.agent.bridge.SandboxMode
import top.ntutn.agent.bridge.storage.SessionKey
import top.ntutn.agent.bridge.storage.SessionStore

/** ChatService-level /new delivery order tests, split from the runner-level DoubaoCliRunnerTest. */
class DoubaoSessionControlTest {
    @TempDir lateinit var temp: Path
    private val mode = SandboxMode.FULL_ACCESS

    private fun runner(): DoubaoCliRunner {
        val executable = temp.resolve("doubao")
        Files.writeString(executable, javaClass.getResource("/fake-doubao.py")!!.readText())
        check(executable.toFile().setExecutable(true))
        val (binary, arguments) = testScriptCommand(executable)
        return DoubaoCliRunner(
            BackendSpec(
                BackendId.DOUBAO,
                binary,
                temp.resolve("runtime"),
                sessionRegistryRoot = temp.resolve("session-registry"),
                binaryArguments = arguments,
            ),
            mode,
        )
    }

    private fun key(): SessionKey {
        val runtime = temp.resolve("runtime")
        return SessionKey(
            "cli_test",
            "private",
            temp.toRealPath().toString(),
            runtime.toString(),
            BackendId.DOUBAO.configValue,
        )
    }

    private class RecordingCards(private val finishResult: Boolean) : CardReplies {
        val finished = CopyOnWriteArrayList<Triple<String, String, String>>()

        override suspend fun create(route: ReplyRoute) =
            CardReference("card-${route.messageId}", route.messageId, route.chatId)

        override suspend fun progress(card: CardReference, progress: AgentProgress) {}

        override suspend fun finish(
            card: CardReference,
            text: String,
            process: String,
            status: String,
        ): Boolean {
            finished += Triple(text, process, status)
            return finishResult
        }
    }

    private fun service(
        runner: DoubaoCliRunner,
        cards: CardReplies,
        textReplies: MutableList<String>,
        storeName: String,
    ) =
        ChatService(
            runner,
            SessionStore(temp.resolve(storeName)),
            { key() },
            sandboxMode = mode,
            cardReplies = cards,
            sender =
                ReplySender { _, text ->
                    textReplies.add(text)
                    CompletableFuture.completedFuture(Unit)
                },
        )

    @Test
    fun `new announces the switch first and merges confirmation into the result card`(): Unit =
        runBlocking {
            val runner = runner()
            val key = key()
            // Append-only ordered log: index expresses delivery order without flaky last() checks.
            val textReplies = CopyOnWriteArrayList<String>()
            val cards = RecordingCards(finishResult = true)
            service(runner, cards, textReplies, "store/card-sessions.json").use {
                withTimeout(10_000) {
                    it.accept(ReplyRoute("private", "first"), "你好")
                    // admit resolves on admission, not completion: wait for the first run to settle.
                    while (it.deploymentStatus()["running"].asInt != 0) {
                        delay(20)
                    }
                }
                val previous = it.sessions.get(key)
                assertNotNull(previous)
                withTimeout(10_000) {
                    it.accept(ReplyRoute("private", "new-card"), "/new 第一项任务")
                    while (it.deploymentStatus()["running"].asInt != 0 ||
                        it.deploymentStatus()["pending"].asInt != 0 ||
                        it.sessions.get(key) == previous
                    ) {
                        delay(20)
                    }
                }
                // The switching notice precedes every other text reply for this command.
                val switchIndex =
                    textReplies.indexOfFirst { text -> text.contains("正在创建并切换到新会话") }
                assertTrue(switchIndex >= 0)
                assertTrue(textReplies.drop(switchIndex + 1).none { text -> text.contains("正在创建") })
                // The earlier ordinary turn also delivered a card; inspect the /new card only.
                val final = cards.finished.last()
                assertTrue(final.first.contains("final"))
                assertTrue(final.first.contains("已创建并切换到新会话"))
                assertTrue(final.first.contains("/resume $previous"))
                assertTrue(final.first.contains(it.sessions.get(key)!!))
            }
        }

    @Test
    fun `failed card delivery falls back to ordinary text with both answer and confirmation`():
        Unit = runBlocking {
            val runner = runner()
            val textReplies = CopyOnWriteArrayList<String>()
            val cards = RecordingCards(finishResult = false)
            service(runner, cards, textReplies, "store/fallback-sessions.json").use {
                withTimeout(10_000) {
                    it.accept(ReplyRoute("private", "new-fallback"), "/new 第一项任务").await()
                }
            }
            // Card finalization failed: the same content must be re-sent as fallback text.
            val fallback = textReplies.filter { text -> text.contains("已创建并切换到新会话") }
            assertEquals(1, fallback.size)
            assertTrue(fallback.single().contains("final"))
            assertTrue(fallback.single().contains("/resume").not() || fallback.single().contains("无"))
        }

    @Test
    fun `draining during bootstrap stops the turn and keeps the binding unchanged`(): Unit =
        runBlocking {
            val runner = runner()
            val key = key()
            val textReplies = CopyOnWriteArrayList<String>()
            val service = service(runner, RecordingCards(true), textReplies, "store/drain-sessions.json")
            service.use {
                val accepted = it.accept(ReplyRoute("private", "draining-new"), "/new wait")
                withTimeout(5000) {
                    while (!Files.exists(temp.resolve("waiting"))) {
                        delay(20)
                    }
                }
                it.drain()
                withTimeout(10_000) {
                    accepted
                    while (it.deploymentStatus()["running"].asInt != 0 ||
                        it.deploymentStatus()["pending"].asInt != 0
                    ) {
                        delay(20)
                    }
                }
                // The bootstrap was stopped, so no new session binding was persisted.
                val replies = textReplies.joinToString("\n")
                assertTrue(replies.contains("正在创建并切换到新会话"))
                assertTrue(
                    replies.contains("已停止") || it.sessions.get(key) == null,
                    "stopped bootstrap must not switch the binding",
                )
            }
        }
}
