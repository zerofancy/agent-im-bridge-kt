package top.ntutn.agent.bridge

import java.util.concurrent.CompletableFuture
import kotlin.system.exitProcess

/** Hands process exit to main so shutdown hooks can join the requesting service's coroutines. */
internal class ProcessExitRequest {
    private val exitCode = CompletableFuture<Int>()

    fun request(code: Int) {
        exitCode.complete(code)
    }

    fun awaitAndExit(): Nothing {
        // Only the application's main thread waits here, outside all service coroutine scopes.
        exitProcess(exitCode.get())
    }
}
