package top.ntutn.agent.bridge.feishu

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeishuDocumentCommentsTest {
    @Test fun `comment document URLs respect platform and document type`() {
        fun reference(type: String) = top.ntutn.agent.bridge.DocumentReference(type, "token")
        assertEquals("https://feishu.cn/docx/token", commentDocumentUrl(reference("docx"), "feishu"))
        assertEquals("https://larksuite.com/docx/token", commentDocumentUrl(reference("docx"), "lark"))
        assertEquals("https://feishu.cn/sheets/token", commentDocumentUrl(reference("sheet"), "feishu"))
        assertEquals("https://feishu.cn/base/token", commentDocumentUrl(reference("bitable"), "feishu"))
        assertNull(commentDocumentUrl(reference("unknown"), "feishu"))
    }

    @Test fun `parse comment payload extracts text and bot mention from structured elements`() {
        val payload = parseCommentPayload(
            """
            {
              "data": {
                "from_user_id": { "open_id": "ou_user" },
                "content": {
                  "elements": [
                    { "type": "text_run", "text_run": { "text": "请看这个文档 " } },
                    { "type": "docs_link", "docs_link": { "url": "https://bytedance.larkoffice.com/docx/AbCdEf123" } },
                    { "type": "person", "person": { "open_id": "ou_bot", "name": "Xor" } }
                  ]
                }
              }
            }
            """.trimIndent(),
            botOpenId = "ou_bot"
        )
        assertEquals("ou_user", payload.authorOpenId)
        assertTrue(payload.mentionedBot)
        assertEquals("请看这个文档 https://bytedance.larkoffice.com/docx/AbCdEf123", payload.text)
    }

    @Test fun `parse comment payload ignores non bot mentions and tolerates sparse shapes`() {
        val payload = parseCommentPayload(
            """
            {
              "data": {
                "user_id": "ou_user",
                "content": {
                  "elements": [
                    { "type": "person", "person": { "open_id": "ou_other", "name": "Alice" } },
                    { "type": "text_run", "text_run": { "text": " 帮我总结一下" } }
                  ]
                }
              }
            }
            """.trimIndent(),
            botOpenId = "ou_bot"
        )
        assertEquals("ou_user", payload.authorOpenId)
        assertFalse(payload.mentionedBot)
        assertEquals("@Alice 帮我总结一下", payload.text)
    }

    @Test fun `parse comment api failure extracts code msg and log id`() {
        val failure = parseCommentApiFailure(
            """
            {
              "code": 234003,
              "msg": "File not in msg.",
              "error": {
                "log_id": "20260910222440DC06369869085B890FC1",
                "troubleshooter": "https://open.feishu.cn/search"
              }
            }
            """.trimIndent()
        )
        assertEquals(234003, failure.code)
        assertEquals("File not in msg.", failure.msg)
        assertEquals("20260910222440DC06369869085B890FC1", failure.logId)
    }

    @Test fun `parse comment api failure tolerates blank or invalid payload`() {
        assertEquals(FeishuCommentApiFailure(null, null, null), parseCommentApiFailure(null))
        assertEquals(FeishuCommentApiFailure(null, null, null), parseCommentApiFailure(""))
        val failure = parseCommentApiFailure("not-json")
        assertNull(failure.code)
        assertNull(failure.msg)
        assertNull(failure.logId)
    }
}
