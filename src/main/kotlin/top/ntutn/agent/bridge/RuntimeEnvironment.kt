package top.ntutn.agent.bridge

import com.google.gson.JsonObject
import java.nio.file.Files
import java.nio.file.Path

/** All mutable runtime paths are selected explicitly, never inherited from a model's environment. */
data class RuntimeEnvironment(val root: Path, val name: String, val release: String) {
    init { require(name in setOf("prod", "dev")); require(release.matches(Regex("[A-Za-z0-9._-]+"))) }
    val directory: Path = root.resolve("environments/$name")
    val settings: JsonObject get() = JsonFiles.read(directory.resolve("runtime.json")) ?: error("缺少环境 runtime.json，请使用 bridgectl 初始化")
    val attachments: Path get() = directory.resolve("attachments")
    val temporary: Path get() = directory.resolve("tmp")
    val releaseDirectory: Path get() = root.resolve("releases/$release")
    fun options(): RunOptions {
        val s = settings
        return RunOptions(Path.of(s["workspace"].asString).toRealPath(), s.string("codexBinary") ?: "codex", true,
            s["maxConcurrentRuns"]?.asInt ?: 10, s.string("traexBinary") ?: "traex", s.string("opencodeBinary") ?: "opencode")
    }
    fun backend(id: BackendId, options: RunOptions): BackendSpec {
        val s = settings
        fun path(key: String, fallback: String) = canonicalDirectory(Path.of(s.string(key) ?: directory.resolve(fallback).toString()))
        return when (id) {
            BackendId.OPENCODE -> BackendSpec(id, options.opencodeBinary, path("opencodeHome", "backend/opencode"))
            BackendId.CODEX -> BackendSpec(id, options.binary, path("codexHome", "backend/codex"))
            BackendId.TRAEX -> BackendSpec(id, options.traexBinary, path("traeCliHome", "backend/trae/cli"), path("traeHome", "backend/trae"))
        }.copy(temporaryRoot = temporary)
    }
    fun validate(config: BridgeConfig) {
        val other = root.resolve("environments/${if (name == "prod") "dev" else "prod"}")
        fun real(p: Path): Path = realRuntimePath(p)
        fun overlap(a: Path, b: Path) = a.startsWith(b) || b.startsWith(a)
        require(!overlap(real(directory), real(other))) { "环境状态目录重叠" }
        val own = settings
        val theirs = JsonFiles.read(other.resolve("runtime.json"))
        val otherConfig = JsonFiles.read(other.resolve("config.json"))
        require(otherConfig?.string("appId") != config.appId) { "调试和正式环境不能使用相同机器人" }
        fun roots(s: JsonObject, base: Path): List<Path> = listOf(
            Path.of(s["workspace"].asString), Path.of(s.string("codexHome") ?: base.resolve("backend/codex").toString()),
            Path.of(s.string("traeHome") ?: base.resolve("backend/trae").toString()),
            Path.of(s.string("opencodeHome") ?: base.resolve("backend/opencode").toString()),
            Path.of(s.string("traeCliHome") ?: base.resolve("backend/trae/cli").toString())).map(::real)
        if (theirs != null) require(roots(own, directory).none { a -> roots(theirs, other).any { b -> overlap(a, b) } }) { "调试和正式的模型目录或工作目录重叠" }
        val ownRoots = roots(own, directory)
        require(ownRoots.none { overlap(it, real(other)) || overlap(it, real(root.resolve("releases"))) }) { "工作目录或模型目录不能覆盖其他环境及发布目录" }
        JsonFiles.directory(temporary)
        JsonFiles.directory(attachments)
    }
    fun verifyClasspath() {
        val actual = Path.of(RuntimeEnvironment::class.java.protectionDomain.codeSource.location.toURI()).toRealPath()
        require(actual.startsWith(releaseDirectory.toRealPath().resolve("lib"))) { "禁止直接从构建目录运行，请使用 bridgectl dev/start" }
    }
    companion object {
        fun current(): RuntimeEnvironment {
            val root = System.getenv("BRIDGE_ROOT") ?: throw IllegalArgumentException("请使用 bridgectl dev 或 bridgectl start --env prod；不再直接运行构建产物")
            return RuntimeEnvironment(Path.of(root).toRealPath(), System.getenv("BRIDGE_ENV") ?: "dev",
                System.getenv("BRIDGE_RELEASE") ?: throw IllegalArgumentException("缺少不可变发布版本"))
        }
    }
}

/** Resolve existing ancestors as well: a not-yet-created child of a symlink must not bypass isolation. */
internal fun realRuntimePath(path: Path): Path {
    val absolute = path.toAbsolutePath().normalize()
    if (Files.exists(absolute)) return absolute.toRealPath()
    val parent = absolute.parent ?: return absolute
    return realRuntimePath(parent).resolve(absolute.fileName)
}
