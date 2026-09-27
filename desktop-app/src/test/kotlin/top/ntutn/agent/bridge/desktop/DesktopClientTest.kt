package top.ntutn.agent.bridge.desktop

import java.io.IOException
import java.nio.file.Path
import kotlin.test.*

class DesktopClientTest {
    @Test fun `snapshots reject stale instances and unsupported API versions`() {
        assertFailsWith<IOException> { decodeSnapshot("""{"runtime":{"apiVersion":1,"bootId":"old"}}""", "new") }
        assertFailsWith<IOException> { decodeSnapshot("""{"runtime":{"apiVersion":2,"bootId":"new"}}""", "new") }
        assertEquals("new", decodeSnapshot("""{"runtime":{"apiVersion":1,"bootId":"new"}}""", "new")
            .getAsJsonObject("runtime").text("bootId"))
    }
    @Test fun `development and production discovery are independent`() {
        val root = Path.of("/tmp/bridge-test")
        assertNotEquals(ConnectionTarget(root, "dev").endpointPath, ConnectionTarget(root, "prod").endpointPath)
        assertFailsWith<IllegalArgumentException> { ConnectionTarget(root, "other") }
    }
}
