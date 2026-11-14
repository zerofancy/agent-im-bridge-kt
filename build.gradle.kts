import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
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
    implementation("com.twelvemonkeys.imageio:imageio-webp:3.12.0")
    implementation(libs.slf4j.api)
    runtimeOnly(libs.logback.classic)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.logback.classic)
    testImplementation(libs.okhttp.mockwebserver)
    testRuntimeOnly(libs.junit.platform.launcher)
}

kotlin { jvmToolchain(21) }

application { mainClass.set("top.ntutn.agent.bridge.MainKt") }

tasks.test { useJUnitPlatform() }

tasks.named<JavaExec>("run") { standardInput = System.`in` }

tasks.processResources {
    from("assets/branding/agent-bridge-app-icon.ico") {
        into("local-files")
        rename { "favicon.ico" }
    }
}

tasks.register<JavaExec>("desktopSmokeServer") {
    group = "verification"
    description = "Run an isolated desktop UI fixture with simulated responses and temporary state"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("top.ntutn.agent.bridge.desktop.DesktopSmokeServerKt")
}

// Runtime snapshots are published outside build/. The generated distribution is never a live
// classpath.
val deepSeekRuntimeDirectory = layout.buildDirectory.dir("deepseek-runtime")
val prepareDeepSeekRuntime by
    tasks.registering(Exec::class) {
        group = "distribution"
        description =
            "Stage the pinned DeepSeek CLI and production dependencies for an immutable release"
        inputs.files(fileTree("deployment/deepseek") { exclude("__pycache__/**", "tests/**") })
        outputs.dir(deepSeekRuntimeDirectory)
        workingDir(projectDir)
        commandLine(
            "python3",
            "deployment/deepseek/prepare_runtime.py",
            "--output",
            deepSeekRuntimeDirectory.get().asFile.absolutePath,
        )
    }

distributions {
    main {
        contents {
            from(
                listOf(
                    "deployment/bridgectl.py",
                    "deployment/launcher.py",
                    "deployment/launcher_watchdog.py",
                    "deployment/process_tree.py",
                    "deployment/remote_access.py",
                    "deployment/state_files.py",
                    "deployment/model_setup.py",
                    "deployment/deepseek_setup.py",
                )
            ) {
                into("libexec")
            }
            from("deployment/deepseek") {
                include(
                    "dsh",
                    "launcher.py",
                    "bridge.patch.yml",
                    "bridge-policy.mjs",
                    "policy-core.mjs",
                    "auth-status.mjs",
                    "auth-core.mjs",
                    "auth.patch.yml",
                    "oauth-login.mjs",
                )
                into("libexec/deepseek")
                eachFile {
                    if (name == "dsh") {
                        permissions { unix("755") }
                    }
                }
            }
            from(prepareDeepSeekRuntime) {
                include("runtime/**")
                into("libexec/deepseek")
            }
            from("deployment/bridgectl") {
                into("bin")
                filePermissions { unix("755") }
            }
        }
    }
}

val deploymentTest by
    tasks.registering(Exec::class) {
        // 部署事务测试依赖 POSIX 的 fcntl 与 launchd/systemd，Windows 上跳过（见 docs/windows-porting-plan.md）。
        onlyIf { !System.getProperty("os.name").lowercase(getDefault()).startsWith("windows") }
        commandLine("python3", "-m", "unittest", "discover", "-s", "deployment", "-p", "test_*.py")
    }

tasks.test { dependsOn(deploymentTest) }

val deepSeekPolicyTest by
    tasks.registering(Exec::class) {
        group = "verification"
        description =
            "Verify the DeepSeek permission policy and release launcher without model access"
        inputs.dir("deployment/deepseek")
        workingDir(projectDir)
        doFirst {
            val sources =
                fileTree("deployment/deepseek/tests") { include("*.test.mjs") }
                    .files
                    .sortedBy { it.path }
            require(sources.isNotEmpty()) { "DeepSeek policy tests are missing" }
            commandLine(listOf("node", "--test") + sources.map { it.absolutePath })
        }
    }

tasks.test { dependsOn(deepSeekPolicyTest) }

val deepSeekLauncherTest by
    tasks.registering(Exec::class) {
        group = "verification"
        description = "Verify the controlled DeepSeek launcher and credential metadata boundary"
        inputs.files(
            "deployment/deepseek/launcher.py",
            "deployment/deepseek/tests/test_launcher.py",
        )
        workingDir(projectDir)
        commandLine(
            "python3",
            "-m",
            "unittest",
            "discover",
            "-s",
            "deployment/deepseek/tests",
            "-p",
            "test_*.py",
        )
    }

tasks.test { dependsOn(deepSeekLauncherTest) }

tasks.named<Sync>("installDist") {
    doFirst {
        require(
            destinationDir.canonicalFile
                .toPath()
                .startsWith(layout.buildDirectory.get().asFile.canonicalFile.toPath())
        ) {
            "installDist 只能写入 build；请使用 bridgectl publish/deploy 发布不可变版本"
        }
        val legacyLock =
            Path.of(System.getProperty("user.home"), ".agent-im-bridge-kt", "bridge.lock")
        if (Files.exists(legacyLock)) {
            FileChannel.open(legacyLock, StandardOpenOption.WRITE).use { channel ->
                val lock = channel.tryLock()
                require(lock != null) { "旧版 Bridge 仍在运行；先停止旧实例，禁止覆盖运行中的安装目录" }
                lock.release()
            }
        }
    }
    doLast {
        val verification =
            providers.exec {
                workingDir(projectDir)
                commandLine(
                    "python3",
                    "deployment/verify_deepseek_distribution.py",
                    destinationDir.absolutePath,
                )
                isIgnoreExitValue = true
            }
        require(verification.result.get().exitValue == 0) {
            "DeepSeek distribution verification failed; rebuild the pinned runtime"
        }
    }
}
