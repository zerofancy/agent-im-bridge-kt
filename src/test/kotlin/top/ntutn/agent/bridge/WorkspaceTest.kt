package top.ntutn.agent.bridge

import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*
import kotlinx.coroutines.*

class WorkspaceTest {
    @TempDir lateinit var temp: Path
    private fun key(chat: String) = SessionKey("cli_test", chat, temp.toRealPath().toString(), temp.resolve("codex").toString())

    @Test fun `path syntax is literal and directories must exist`(): Unit = runBlocking {
        val nested = Files.createDirectories(temp.resolve("dir with spaces"))
        assertEquals(nested.toRealPath(), resolveWorkspace("'dir with spaces'", temp))
        assertEquals(nested.toRealPath(), resolveWorkspace("\"$nested\"", temp))
        assertEquals(temp.toRealPath(), resolveWorkspace("..", nested))
        assertEquals(Path.of(System.getProperty("user.home")).toRealPath(), resolveWorkspace("~", temp))
        val link = Files.createSymbolicLink(temp.resolve("link"), nested)
        assertEquals(nested.toRealPath(), resolveWorkspace(link.toString(), temp))
        val literal = Files.createDirectory(temp.resolve("\$(literal)"))
        assertEquals(literal.toRealPath(), resolveWorkspace("\$(literal)", temp))
        for (bad in listOf("missing", "''", "\"mismatch", "file")) {
            Files.writeString(temp.resolve("file"), "file")
            assertFailsWith<IllegalArgumentException> { resolveWorkspace(bad, temp) }
        }
        assertTrue(isCdCommand("/cd\t..")); assertFalse(isCdCommand("/cdrom"))
    }

    @Test fun `v1 migrates and workspace switch clears only target binding atomically`(): Unit = runBlocking {
        val file = temp.resolve("sessions.json")
        Files.writeString(file, """{"version":1,"sessions":[]}""")
        val store = SessionStore(file)
        val a = key("a"); val b = key("b")
        val target = Files.createDirectory(temp.resolve("target")).toRealPath()
        val id = UUID.randomUUID().toString()
        store.set(a.copy(workspace = target.toString()), id)
        store.set(b, id)
        store.changeWorkspace(a, target)
        val restored = SessionStore(file)
        assertEquals(target, restored.workspace(a))
        assertEquals(Path.of(b.workspace), restored.workspace(b))
        assertNull(restored.get(a.copy(workspace = target.toString())))
        assertEquals(id, restored.get(b))
        assertEquals(3, JsonParser.parseString(Files.readString(file)).asJsonObject["version"].asInt)
        Files.delete(file); Files.createDirectory(file)
        assertFailsWith<SessionPersistenceException> { store.changeWorkspace(a, temp.toRealPath()) }
        assertEquals(target, store.workspace(a))
        assertEquals(id, store.get(b))
    }

    @Test fun `selecting same directory preserves session and persists explicit selection`(): Unit = runBlocking {
        val file = temp.resolve("sessions.json")
        val store = SessionStore(file)
        val base = key("a")
        val id = UUID.randomUUID().toString()
        store.set(base, id)
        store.changeWorkspace(base, Path.of(base.workspace))
        val restored = SessionStore(file)
        assertEquals(id, restored.get(base))
        assertEquals(Path.of(base.workspace), restored.workspace(base.copy(workspace = "/another-default")))
    }

    @Test fun `cd rejects busy chat switches fresh sessions and restores directory after restart`(): Unit = runBlocking {
        val target = Files.createDirectory(temp.resolve("target")).toRealPath()
        val store = SessionStore(temp.resolve("sessions.json"))
        val calls = mutableListOf<Triple<String, Path, String?>>()
        val replies = mutableListOf<Pair<ReplyRoute, String>>()
        val started = CountDownLatch(1); val release = CountDownLatch(1)
        val runner = object : AgentRunner {
            override suspend fun run(prompt: String, sessionId: String?, workspace: Path, sandboxMode: SandboxMode, onSession: suspend (String) -> Unit): AgentResult {
                calls += Triple(prompt, workspace, sessionId)
                assertEquals(SandboxMode.WORKSPACE_WRITE, sandboxMode)
                val id = sessionId ?: UUID.randomUUID().toString()
                onSession(id)
                if (prompt == "first") { started.countDown(); release.await(5, TimeUnit.SECONDS) }
                return AgentResult.Success("ok", id)
            }
            override fun close() {}
        }
        val sender = ReplySender { route, text -> synchronized(replies) { replies += route to text }; CompletableFuture.completedFuture(Unit) }
        ChatService(runner, store, ::key, 1, SandboxMode.WORKSPACE_WRITE, sender = sender).use { svc ->
            val route = ReplyRoute("a", "m1")
            val first = svc.accept(route, "first")
            assertTrue(started.await(5, TimeUnit.SECONDS))
            val cd = svc.accept(route.copy(messageId = "cd"), "/cd target")
            cd.get(5, TimeUnit.SECONDS)
            assertTrue(replies.any { it.first.messageId == "cd" && it.second.contains("忙碌") })
            release.countDown()
            first.get(5, TimeUnit.SECONDS)
            svc.accept(route, "/cd target").get(5, TimeUnit.SECONDS)
            svc.accept(route, "second").get(5, TimeUnit.SECONDS)
            svc.accept(route, "/cd .").get(5, TimeUnit.SECONDS)
            svc.accept(route, "same").get(5, TimeUnit.SECONDS)
            svc.accept(route, "/cd ..").get(5, TimeUnit.SECONDS)
            svc.accept(route, "back").get(5, TimeUnit.SECONDS)
            svc.accept(route, "/cd target").get(5, TimeUnit.SECONDS)
            svc.accept(route, "again").get(5, TimeUnit.SECONDS)
            assertEquals(listOf("first", "second", "same", "back", "again"), calls.map { it.first })
            assertEquals(listOf(Path.of(key("a").workspace), target, target, Path.of(key("a").workspace), target), calls.map { it.second })
            assertNull(calls[1].third); assertNotNull(calls[2].third); assertNull(calls[3].third); assertNull(calls[4].third)
            assertFalse(replies.any { it.first.messageId == "cd" && it.second == "正在处理…" })
        }
        assertEquals(target, SessionStore(temp.resolve("sessions.json")).workspace(key("a")))
        assertEquals(Path.of(key("b").workspace), store.workspace(key("b")))
    }

    @Test fun `failed cd reply keeps committed directory and invalid cd keeps original`(): Unit = runBlocking {
        val target = Files.createDirectory(temp.resolve("target")).toRealPath()
        val store = SessionStore(temp.resolve("sessions.json"))
        val runner = object : AgentRunner {
            override suspend fun run(prompt: String, sessionId: String?, workspace: Path, sandboxMode: SandboxMode, onSession: suspend (String) -> Unit): AgentResult = error("commands must not run Codex")
            override fun close() {}
        }
        ChatService(runner, store, ::key, sender = ReplySender { _, _ -> CompletableFuture.failedFuture(IllegalArgumentException()) }).use {
            it.accept(ReplyRoute("a", "cd"), "/cd target").get(5, TimeUnit.SECONDS)
            assertEquals(target, store.workspace(key("a")))
            it.accept(ReplyRoute("a", "bad"), "/cd missing").get(5, TimeUnit.SECONDS)
            assertEquals(target, store.workspace(key("a")))
        }
    }

    @Test fun `three chats restore distinct directories and missing workspace never runs Codex`(): Unit = runBlocking {
        val file = temp.resolve("sessions.json")
        val store = SessionStore(file)
        val calls = mutableListOf<Path>()
        val runner = object : AgentRunner {
            override suspend fun run(prompt: String, sessionId: String?, workspace: Path, sandboxMode: SandboxMode, onSession: suspend (String) -> Unit): AgentResult {
                calls.add(workspace)
                return AgentResult.Success("ok")
            }
            override fun close() {}
        }
        val sender = ReplySender { _, _ -> CompletableFuture.completedFuture(Unit) }
        val targets = listOf("private", "group-a", "group-b").associateWith {
            Files.createDirectory(temp.resolve(it)).toRealPath()
        }
        ChatService(runner, store, ::key, sender = sender).use { svc ->
            targets.forEach { (chat, path) -> svc.accept(ReplyRoute(chat, chat), "/cd $path").get(5, TimeUnit.SECONDS) }
        }
        ChatService(runner, SessionStore(file), ::key, sender = sender).use { svc ->
            targets.forEach { (chat, _) -> svc.accept(ReplyRoute(chat, chat), "hello").get(5, TimeUnit.SECONDS) }
            assertEquals(targets.values.toList(), calls)
            Files.delete(targets.getValue("group-a"))
            svc.accept(ReplyRoute("group-a", "missing"), "hello").get(5, TimeUnit.SECONDS)
            assertEquals(3, calls.size)
            svc.accept(ReplyRoute("group-a", "recover"), "/cd $temp").get(5, TimeUnit.SECONDS)
            svc.accept(ReplyRoute("group-a", "recovered"), "hello").get(5, TimeUnit.SECONDS)
            assertEquals(temp.toRealPath(), calls.last())
        }
    }

    @Test fun `permission config defaults strictly and round trips`(): Unit = runBlocking {
        val file = temp.resolve("config.json")
        val base = """{"appId":"cli_test","appSecret":"test","allowedUserId":"ou_test","tenant":"feishu""""
        Files.writeString(file, "$base}")
        assertEquals("read-only", ConfigStore(file).load()!!.sandboxMode)
        for (mode in SandboxMode.entries) {
            ConfigStore(file).save(BridgeConfig("cli_test", "test", "ou_test", "feishu", mode.cliValue))
            assertEquals(mode.cliValue, ConfigStore(file).load()!!.sandboxMode)
        }
        for (bad in listOf("null", "123", "true", "{}", "\"unknown\"")) {
            Files.writeString(file, "$base,\"sandboxMode\":$bad}")
            assertFailsWith<IllegalArgumentException> { ConfigStore(file).load() }
        }
    }
}
