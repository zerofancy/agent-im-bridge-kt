package top.ntutn.agent.bridge

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class GroupCommandParsingTest {
    @TempDir lateinit var temp: Path

    @Test
    fun `only complete lowercase group token selects group creation`() {
        assertEquals(NewSessionInput(true, "项目讨论"), NewSessionInput.parse("group 项目讨论"))
        assertEquals(NewSessionInput(true, ""), NewSessionInput.parse("group"))
        assertEquals(
            NewSessionInput(false, "grouping question"),
            NewSessionInput.parse("grouping question"),
        )
        assertEquals(
            NewSessionInput(false, "Group question"),
            NewSessionInput.parse("Group question"),
        )
    }

    @Test
    fun `escape is removed once and group name internal whitespace is preserved`() {
        assertEquals(
            NewSessionInput(false, "group  question\nsecond line"),
            NewSessionInput.parse("-- group  question\nsecond line"),
        )
        assertEquals(NewSessionInput(false, "-- group"), NewSessionInput.parse("-- -- group"))
        assertEquals(NewSessionInput(true, "/stop"), NewSessionInput.parse("group /stop"))
        assertEquals(
            NewSessionInput(true, "one  two\nthree"),
            NewSessionInput.parse("group\n one  two\nthree"),
        )
    }

    @Test
    fun `empty group argument uses default name while ordinary new keeps its input`() {
        assertEquals(BridgeCommand("/new", "group"), BridgeCommand.parse("/new group   \n "))
        assertEquals(NewSessionInput(true, ""), NewSessionInput.parse("group   "))
        assertEquals(NewSessionInput(false, ""), NewSessionInput.parse(""))
        val command = assertNotNull(BridgeCommand.parse("/new group 项目  讨论 😀"))
        assertEquals(NewSessionInput(true, "项目  讨论 😀"), NewSessionInput.parse(command.argument))
    }

    @Test
    fun `recovery directory must be an explicit valid absolute path`() {
        assertTrue(explicitGroupDirectory(temp.toString()))
        assertTrue(explicitGroupDirectory("\"${temp.resolve("project with spaces")}\""))
        assertFalse(explicitGroupDirectory("."))
        assertFalse(explicitGroupDirectory("child"))
        assertFalse(explicitGroupDirectory("/invalid\u0000"))
    }
}
