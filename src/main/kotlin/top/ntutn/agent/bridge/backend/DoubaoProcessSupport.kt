package top.ntutn.agent.bridge.backend

import com.google.gson.JsonParser
import java.io.Reader
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import top.ntutn.agent.bridge.BackendSpec
import top.ntutn.agent.bridge.string

/** Process-level helpers shared by Doubao chat runs and session-control calls. */
internal object DoubaoProcessSupport {
    private val ptyWrapper = Path.of("/usr/bin/script")
    private val ansiEscapeRegex = Regex("\\u001B\\[[;?0-9]*[ -/]*[@-~]")
    private val backspaceOverstrikeRegex = Regex(".\\u0008")
    private val controlCharsRegex =
        Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F]")
    private val listIdRegex = Regex("\\d{8,}")

    fun command(
        backend: BackendSpec,
        prompt: String,
        sessionId: String?,
        workspace: Path,
    ): List<String> {
        val base =
            mutableListOf<String>().apply {
                add(backend.binary)
                addAll(backend.binaryArguments)
                add("-C")
                add(workspace.toString())
                if (sessionId != null) {
                    add("--resume")
                    add(sessionId)
                }
                add("--dangerously-bypass-approvals-and-sandbox")
                add("-p")
                add(prompt)
            }
        return if (Files.isExecutable(ptyWrapper)) {
            listOf(ptyWrapper.toString(), "-q", "/dev/null") + base
        } else {
            base
        }
    }

    suspend fun start(
        backend: BackendSpec,
        prompt: String,
        sessionId: String?,
        workspace: Path,
    ): Process =
        withContext(Dispatchers.IO) {
            Files.createDirectories(backend.runtimeRoot)
            ProcessBuilder(command(backend, prompt, sessionId, workspace))
                .directory(workspace.toFile())
                .apply {
                    environment().putAll(backend.environment)
                    backend.temporaryRoot?.let {
                        environment()["TMPDIR"] = it.toString()
                        environment()["TMP"] = it.toString()
                        environment()["TEMP"] = it.toString()
                    }
                }
                .start()
        }

    // --list is account-global on the real CLI and has no JSON mode, so only the leading id is
    // parsed; workspace ownership must come from the Bridge-side registry instead.
    fun parseList(output: String): Set<String> =
        output
            .lineSequence()
            .map { sanitizeLine(it).trim().substringBefore(' ').trim() }
            .filter { it.matches(listIdRegex) }
            .toSet()

    fun sessionEvent(line: String): String? =
        try {
            val json = JsonParser.parseString(line).asJsonObject
            if (json.string("type") == "session") {
                json.string("session_id")?.takeIf { it.isNotBlank() }
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }

    fun sanitizeLine(line: String): String =
        line
            .replace(ansiEscapeRegex, "")
            .replace(backspaceOverstrikeRegex, "")
            .replace(controlCharsRegex, "")

    suspend fun readOutput(reader: Reader, consume: suspend (String, Boolean) -> Unit) {
        val pending = StringBuilder()
        val buffer = CharArray(256)
        while (true) {
            val count = runInterruptible { reader.read(buffer) }
            if (count < 0) {
                break
            }
            pending.append(buffer, 0, count)
            while (true) {
                val newline = pending.indexOf("\n")
                if (newline < 0) {
                    break
                }
                val line = sanitizeLine(pending.substring(0, newline).removeSuffix("\r"))
                pending.delete(0, newline + 1)
                consume(line, false)
            }
        }
        if (pending.isNotEmpty()) {
            consume(sanitizeLine(pending.toString()), true)
        }
    }

    fun terminate(process: Process?) {
        if (process == null) {
            return
        }
        try {
            val handles =
                process
                    .toHandle()
                    .descendants()
                    .use { descendants -> descendants.toArray().map { it as ProcessHandle } }
                    .reversed() + process.toHandle()
            handles.filter { it.isAlive }.forEach { it.destroy() }
            if (!process.waitFor(500, TimeUnit.MILLISECONDS)) {
                handles.filter { it.isAlive }.forEach { it.destroyForcibly() }
                process.waitFor(500, TimeUnit.MILLISECONDS)
            }
        } catch (_: Exception) {
            process.destroyForcibly()
        }
    }
}
