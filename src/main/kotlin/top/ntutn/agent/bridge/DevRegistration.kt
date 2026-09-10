package top.ntutn.agent.bridge

import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

/** Foreground setup only; launchd never invokes the interactive authorization flow. */
object DevRegistration {
    @JvmStatic
    fun main(args: Array<String>) {
        FatalErrorHandler.install()
        try {
            require(args.size == 2)
            val destination = Path.of(args[0])
            require(!Files.exists(destination))
            val config = register(false)
            val production = JsonFiles.read(Path.of(args[1]))
            if (production?.get("appId")?.asString == config.appId) {
                System.err.println("选中了正式机器人；请重新运行 dev，选择或创建另一个测试机器人。正式服务未改动。")
                exitProcess(1)
            }
            ConfigStore(destination).save(config)
            // The short-lived setup process must not wait for SDK connection-pool threads.
            exitProcess(0)
        } catch (e: Exception) {
            FatalErrorHandler.rethrowProgrammingError(e)
            System.err.println(safeError(e))
            exitProcess(1)
        }
    }
}
