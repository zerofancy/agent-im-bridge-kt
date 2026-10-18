package top.ntutn.agent.bridge

import java.lang.reflect.Modifier
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import org.junit.jupiter.api.extension.AfterEachCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.io.CleanupMode
import org.junit.jupiter.api.io.TempDir

/** Retry transient Windows directory deletion before JUnit's immediate cleanup attempts. */
class WindowsTempDirectoryCleanup : AfterEachCallback {
    override fun afterEach(context: ExtensionContext) {
        if (!PlatformFiles.isWindows) {
            return
        }
        val instance = context.requiredTestInstance
        var type: Class<*>? = instance.javaClass
        while (type != null) {
            for (field in type.declaredFields) {
                val annotation = field.getAnnotation(TempDir::class.java) ?: continue
                if (
                    Modifier.isStatic(field.modifiers) ||
                        annotation.cleanup == CleanupMode.NEVER ||
                        (annotation.cleanup == CleanupMode.ON_SUCCESS &&
                            context.executionException.isPresent)
                ) {
                    continue
                }
                field.isAccessible = true
                val root = field.get(instance) as? Path ?: continue
                retryDirectoryDeletion {
                    if (Files.exists(root)) {
                        Files.walkFileTree(
                            root,
                            object : SimpleFileVisitor<Path>() {
                                override fun visitFile(
                                    file: Path,
                                    attrs: BasicFileAttributes,
                                ): FileVisitResult {
                                    Files.deleteIfExists(file)
                                    return FileVisitResult.CONTINUE
                                }

                                override fun postVisitDirectory(
                                    dir: Path,
                                    error: java.io.IOException?,
                                ): FileVisitResult {
                                    if (error != null) {
                                        throw error
                                    }
                                    Files.deleteIfExists(dir)
                                    return FileVisitResult.CONTINUE
                                }
                            },
                        )
                    }
                }
            }
            type = type.superclass
        }
    }
}

internal fun retryDirectoryDeletion(pause: () -> Unit = { Thread.sleep(25) }, delete: () -> Unit) {
    repeat(10) { attempt ->
        try {
            delete()
            return
        } catch (error: DirectoryNotEmptyException) {
            if (attempt == 9) {
                throw error
            }
            pause()
        }
    }
}
