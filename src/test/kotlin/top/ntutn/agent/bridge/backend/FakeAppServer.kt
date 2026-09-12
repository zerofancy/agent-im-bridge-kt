package top.ntutn.agent.bridge.backend

import top.ntutn.agent.bridge.*
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path

internal fun fakeAppServer(temp: Path): Path {
    val binary = temp.resolve("fake codex")
    Files.writeString(binary, object {}.javaClass.getResource("/fake-app-server.py")!!.readText())
    check(binary.toFile().setExecutable(true))
    return binary
}
internal fun requests(temp: Path) = if (Files.exists(temp.resolve("requests")))
    Files.readAllLines(temp.resolve("requests")).filter { it.isNotBlank() }.map { JsonParser.parseString(it).asJsonObject }
    else emptyList()
