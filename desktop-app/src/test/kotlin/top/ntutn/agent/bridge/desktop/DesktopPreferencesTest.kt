package top.ntutn.agent.bridge.desktop

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlinx.coroutines.*
import org.junit.jupiter.api.io.TempDir

class DesktopPreferencesTest {
    @TempDir lateinit var temp: Path

    @Test
    fun `saved selection survives restart and explicit CLI fields take precedence`(): Unit =
        runBlocking {
            val file = temp.resolve("preferences.json")
            val selected = ConnectionTarget(temp.resolve("custom-root"), "prod")
            DesktopPreferences(file).save(selected)
            val saved = DesktopPreferences(file).load()
            assertEquals(selected, startupTarget(emptyArray(), saved, temp))
            assertEquals(
                selected.copy(environment = "dev"),
                startupTarget(arrayOf("--env", "dev"), saved, temp),
            )
            assertEquals(
                ConnectionTarget(temp, "prod"),
                startupTarget(arrayOf("--root", temp.toString()), saved, temp),
            )
            assertEquals(selected, DesktopPreferences(file).load())
            assertFailsWith<IllegalArgumentException> {
                startupTarget(arrayOf("--env", "invalid"), saved, temp)
            }
        }

    @Test
    fun `missing or invalid preferences fall back without overwriting the file`() {
        val file = temp.resolve("preferences.json")
        val preferences = DesktopPreferences(file)
        assertNull(preferences.load())
        assertEquals(
            ConnectionTarget(temp, "dev"),
            startupTarget(emptyArray(), preferences.load(), temp),
        )
        for (contents in
            listOf(
                "broken",
                "null",
                "[]",
                "{}",
                "{\"root\":\"relative\",\"environment\":\"prod\"}",
            )) {
            Files.writeString(file, contents)
            assertNull(preferences.load())
            assertEquals(contents, Files.readString(file))
        }
    }

    @Test
    fun `rapid GUI switches persist the last choice even when the window closes`(): Unit =
        runBlocking {
            val file = temp.resolve("preferences.json")
            val preferences = DesktopPreferences(file)
            val job = SupervisorJob()
            val scope = CoroutineScope(job + Dispatchers.Unconfined)
            val first = ConnectionTarget(temp, "dev")
            val last = ConnectionTarget(temp.resolve("custom"), "prod")
            val model = DesktopModel(scope, first, saveTarget = preferences::save)
            try {
                model.connect(first)
                model.connect(last)
                assertEquals(last, model.state.value.target)
            } finally {
                model.close()
                job.cancelAndJoin()
            }
            assertEquals(last, DesktopPreferences(file).load())
            Files.list(temp).use { paths ->
                assertTrue(
                    paths.noneMatch { it.fileName.toString().startsWith(".desktop-preferences-") }
                )
            }
        }
}
