package top.ntutn.agent.bridge

import java.nio.file.AccessDeniedException
import java.nio.file.DirectoryNotEmptyException
import kotlin.test.*

class WindowsTempDirectoryCleanupTest {
    @Test
    fun `transient nonempty directories are retried with a bounded pause`() {
        var attempts = 0
        var pauses = 0
        retryDirectoryDeletion(pause = { pauses++ }) {
            if (++attempts < 3) {
                throw DirectoryNotEmptyException("fixture")
            }
        }
        assertEquals(3, attempts)
        assertEquals(2, pauses)
    }

    @Test
    fun `persistent directory failures still fail and open handles are not ignored`() {
        var pauses = 0
        assertFailsWith<DirectoryNotEmptyException> {
            retryDirectoryDeletion(pause = { pauses++ }) {
                throw DirectoryNotEmptyException("fixture")
            }
        }
        assertEquals(9, pauses)
        assertFailsWith<AccessDeniedException> {
            retryDirectoryDeletion(pause = { fail("access failures must not be retried") }) {
                throw AccessDeniedException("fixture")
            }
        }
    }
}
