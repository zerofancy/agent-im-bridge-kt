import java.nio.file.Path
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.nio.channels.FileChannel
import java.util.Locale
import java.util.Locale.getDefault

plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

repositories { mavenCentral() }

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.lark.oapi)
    implementation(libs.gson)
    implementation(libs.okhttp)
    implementation(libs.slf4j.api)
    runtimeOnly(libs.logback.classic)
    testImplementation(libs.kotlin.test.junit5)
    testRuntimeOnly(libs.junit.platform.launcher)
}

kotlin { jvmToolchain(21) }
application { mainClass.set("top.ntutn.agent.bridge.MainKt") }
tasks.test { useJUnitPlatform() }
tasks.named<JavaExec>("run") { standardInput = System.`in` }

tasks.register<JavaExec>("desktopSmokeServer") {
    group = "verification"
    description = "Run an isolated desktop UI fixture with simulated responses and temporary state"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("top.ntutn.agent.bridge.desktop.DesktopSmokeServerKt")
}

// Runtime snapshots are published outside build/. The generated distribution is never a live classpath.
distributions {
    main {
        contents {
            from("deployment/bridgectl.py") { into("libexec") }
            from("deployment/bridgectl") { into("bin"); filePermissions { unix("755") } }
        }
    }
}

val deploymentTest by tasks.registering(Exec::class) {
    // 部署事务测试依赖 POSIX 的 fcntl 与 launchd/systemd，Windows 上跳过（见 docs/windows-porting-plan.md）。
    onlyIf { !System.getProperty("os.name").lowercase(getDefault()).startsWith("windows") }
    commandLine("python3", "-m", "unittest", "discover", "-s", "deployment", "-p", "test_*.py")
}
tasks.test { dependsOn(deploymentTest) }

tasks.named<Sync>("installDist") {
    doFirst {
        require(destinationDir.canonicalFile.toPath().startsWith(layout.buildDirectory.get().asFile.canonicalFile.toPath())) {
            "installDist 只能写入 build；请使用 bridgectl publish/deploy 发布不可变版本"
        }
        val legacyLock = Path.of(System.getProperty("user.home"), ".agent-im-bridge-kt", "bridge.lock")
        if (Files.exists(legacyLock)) {
            FileChannel.open(legacyLock, StandardOpenOption.WRITE).use { channel ->
                val lock = channel.tryLock()
                require(lock != null) { "旧版 Bridge 仍在运行；先停止旧实例，禁止覆盖运行中的安装目录" }
                lock.release()
            }
        }
    }
}
