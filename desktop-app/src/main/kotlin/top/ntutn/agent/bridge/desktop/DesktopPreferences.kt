package top.ntutn.agent.bridge.desktop

import com.google.gson.JsonParser
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** UI preferences only: never contains endpoint tokens or backend credentials. */
internal class DesktopPreferences(private val path: Path) {
    private val mutex = Mutex()

    /** Called before the GUI starts. Unreadable preferences never prevent connecting manually. */
    fun load(): ConnectionTarget? {
        return try {
            val value = JsonParser.parseString(Files.readString(path)).asJsonObject
            val root = Path.of(value.text("root"))
            if (!root.isAbsolute) {
                null
            } else {
                ConnectionTarget(root.normalize(), value.text("environment"))
            }
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: IllegalStateException) {
            null
        } catch (_: com.google.gson.JsonParseException) {
            null
        }
    }

    /** Finish ordered atomic preference commits even if the window closes during a write. */
    suspend fun save(target: ConnectionTarget) =
        withContext(NonCancellable) {
            mutex.withLock {
                withContext(Dispatchers.IO) {
                    Files.createDirectories(path.parent)
                    val temp = Files.createTempFile(path.parent, ".desktop-preferences-", ".json")
                    try {
                        Files.writeString(
                            temp,
                            objectJson(
                                    "root" to target.root.toAbsolutePath().normalize().toString(),
                                    "environment" to target.environment,
                                )
                                .toString(),
                        )
                        Files.move(temp, path, ATOMIC_MOVE, REPLACE_EXISTING)
                    } finally {
                        Files.deleteIfExists(temp)
                    }
                }
            }
        }
}

/** Explicit CLI fields override saved fields for this launch; they do not rewrite preferences. */
internal fun startupTarget(
    args: Array<String>,
    saved: ConnectionTarget?,
    defaultRoot: Path,
): ConnectionTarget {
    var root = saved?.root ?: defaultRoot
    var environment = saved?.environment ?: "dev"
    require(args.size % 2 == 0) { "用法：--root <状态目录> --env <dev|prod>" }
    args.toList().chunked(2).forEach { (key, value) ->
        when (key) {
            "--root" -> {
                root = Path.of(value).toAbsolutePath().normalize()
            }
            "--env" -> {
                environment = value
            }
            else -> {
                error("未知桌面参数")
            }
        }
    }
    return ConnectionTarget(root.toAbsolutePath().normalize(), environment)
}
