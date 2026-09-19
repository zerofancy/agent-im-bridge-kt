package top.ntutn.agent.bridge.backend

import top.ntutn.agent.bridge.*
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path

internal fun testScriptCommand(script: Path): Pair<String, List<String>> =
    if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true))
        "python" to listOf("-X", "utf8", script.toString())
    else script.toString() to emptyList()

internal fun fakeAppServer(temp: Path, id: BackendId = BackendId.CODEX): BackendSpec {
    val script = temp.resolve("fake-app-server.py")
    Files.writeString(script, object {}.javaClass.getResource("/fake-app-server.py")!!.readText())
    check(script.toFile().setExecutable(true))
    val (binary, arguments) = testScriptCommand(script)
    return BackendSpec(id, binary, temp.resolve("runtime"), temp.resolve("shared"), binaryArguments = arguments)
}
internal fun requests(temp: Path) = if (Files.exists(temp.resolve("requests")))
    Files.readAllLines(temp.resolve("requests")).filter { it.isNotBlank() }.map { JsonParser.parseString(it).asJsonObject }
    else emptyList()
