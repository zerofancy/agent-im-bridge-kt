package top.ntutn.agent.bridge

import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import kotlin.test.*
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.io.TempDir
import top.ntutn.agent.bridge.storage.GroupCreationStore
import top.ntutn.agent.bridge.storage.SessionKey
import top.ntutn.agent.bridge.storage.SessionStore

class GroupInputValidationTest {
    @TempDir lateinit var temp: Path

    @Test
    fun `attachment and names over sixty codepoints are rejected before external creation`(): Unit =
        runBlocking {
            GroupConversationTestFixture(temp).use { fixture ->
                fixture.send(
                    "attachment",
                    "/new group question",
                    input = fixture.input().copy(hasAttachments = true),
                )
                fixture.send("large", "/new group " + "问".repeat(61))
                fixture.send("large-markup", "/new group " + "😀".repeat(61))
                assertTrue(fixture.gateway.creates.isEmpty())
                assertTrue(fixture.runner.creates.isEmpty())
                assertTrue(fixture.runner.calls.isEmpty())
                assertTrue(fixture.response("attachment").contains("文本"))
                assertTrue(fixture.response("large").contains("60"))
                assertTrue(fixture.response("large-markup").contains("60"))
            }
        }

    @Test
    fun `slash group name is forwarded without executing model or nested command`(): Unit =
        runBlocking {
            GroupConversationTestFixture(temp).use { fixture ->
                fixture.send("new", "/new group /stop")
                assertEquals(listOf("/stop"), fixture.gateway.names.toList())
                assertEquals(1, fixture.gateway.posts.size)
                assertTrue(fixture.runner.calls.isEmpty())
            }
        }

    @Test
    fun `failed progress acknowledgement never quarantines unrelated group directories`(): Unit =
        runBlocking {
            val gateway = GroupTestGateway()
            val operations = GroupCreationStore(temp.resolve("groups.json"))
            val runner = GroupTestRunner()
            ChatService(
                    runner,
                    SessionStore(temp.resolve("sessions.json")),
                    { chat ->
                        SessionKey("app", chat, temp.toString(), temp.resolve("codex").toString())
                    },
                    groupConversations = GroupConversations(gateway, operations, "owner"),
                    sender =
                        ReplySender { _, _ ->
                            CompletableFuture.failedFuture(java.io.IOException("send failed"))
                        },
                )
                .use { service ->
                    withTimeout(5000) {
                        service
                            .accept(
                                ReplyRoute("source", "new"),
                                "/new group question",
                                MessageInput("p2p", sender = MessageSender("owner")),
                            )
                            .await()
                    }
                    assertTrue(gateway.creates.isEmpty())
                    assertTrue(runner.calls.isEmpty())
                }
        }

    @Test
    fun `sixty supplementary Unicode codepoints are accepted unchanged`(): Unit = runBlocking {
        GroupConversationTestFixture(temp).use { f ->
            val name = "😀".repeat(60)
            assertEquals(120, name.length)
            f.send("unicode", "/new group $name")
            assertEquals(listOf(name), f.gateway.names.toList())
            assertEquals("READY", f.operations.get(f.gateway.creates.single())?.phase)
            assertTrue(f.runner.calls.isEmpty())
        }
    }

    @Test
    fun `line breaks in group name are rejected before gateway or session creation`(): Unit =
        runBlocking {
            GroupConversationTestFixture(temp).use { f ->
                for ((index, name) in listOf("第一行\n第二行", "第一行\r第二行", "第一行\r\n第二行").withIndex()) {
                    f.send("newline-$index", "/new group $name")
                    assertTrue(f.response("newline-$index").contains("换行"))
                }
                assertTrue(f.gateway.creates.isEmpty())
                assertTrue(f.runner.creates.isEmpty())
                assertTrue(f.runner.calls.isEmpty())
            }
        }
}
