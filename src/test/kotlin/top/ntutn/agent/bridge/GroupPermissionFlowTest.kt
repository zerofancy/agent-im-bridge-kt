package top.ntutn.agent.bridge

import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class GroupPermissionFlowTest {
    @TempDir lateinit var root: Path

    @Test
    fun `missing permission prevents all group writes and explicit retry checks again`() =
        runBlocking {
            GroupConversationTestFixture(root).use { fixture ->
                val link = "https://open.feishu.cn/app/cli_test/auth?q=im%3Achat.members%3Aread"
                fixture.gateway.permissionFailure =
                    GroupPermissionRequired(listOf("im:chat.members:read"), link)
                fixture.send("missing", "/new group 测试")
                assertTrue(fixture.gateway.creates.isEmpty())
                assertTrue(fixture.gateway.verified.isEmpty())
                assertTrue(fixture.gateway.posts.isEmpty())
                assertTrue(fixture.runner.creates.isEmpty())
                assertTrue(fixture.response("missing").contains("未创建群"))
                assertTrue(fixture.response("missing").contains(link))
                assertFalse(fixture.response("missing").contains("正在创建"))
                assertFalse(java.nio.file.Files.exists(root.resolve("groups.json")))
                fixture.gateway.permissionFailure = null
                fixture.send("retry", "/new group 测试")
                assertEquals(2, fixture.gateway.preflights)
                assertEquals(1, fixture.gateway.creates.size)
                assertEquals(1, fixture.gateway.posts.size)
                assertTrue(fixture.runner.calls.isEmpty())
            }
        }

    @Test
    fun `permission revoked after creation preserves group and explains incomplete initialization`() =
        runBlocking {
            GroupConversationTestFixture(root).use { fixture ->
                fixture.gateway.verifyException =
                    GroupPermissionRequired(
                        listOf("im:chat.members:read"),
                        "https://open.feishu.cn/app/cli_test/auth",
                    )
                fixture.send("revoked", "/new group")
                val response = fixture.response("revoked")
                assertEquals(1, fixture.gateway.creates.size)
                assertTrue(response.contains("群已创建，但初始化未完成"))
                assertTrue(response.contains("https://example.invalid/group/target"))
                assertTrue(response.contains("申请权限"))
                assertFalse(response.contains("未创建群"))
                assertTrue(fixture.runner.creates.isEmpty())
                assertTrue(fixture.gateway.posts.isEmpty())
            }
        }

    @Test
    fun `unavailable permission check fails closed`() = runBlocking {
        GroupConversationTestFixture(root).use { fixture ->
            fixture.gateway.permissionFailure = GroupConversationException("无法确认应用身份权限")
            fixture.send("unavailable", "/new group")
            assertTrue(fixture.gateway.creates.isEmpty())
            assertTrue(fixture.response("unavailable").contains("无法确认应用身份权限"))
            assertFalse(fixture.response("unavailable").contains("申请权限"))
        }
    }
}
