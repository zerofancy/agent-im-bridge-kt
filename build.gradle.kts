import java.nio.file.Path
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.nio.channels.FileChannel

plugins {
    kotlin("jvm") version "2.1.21"
    application
}

repositories { mavenCentral() }

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("com.larksuite.oapi:oapi-sdk:2.7.3")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("org.slf4j:slf4j-api:2.0.17")
    runtimeOnly("ch.qos.logback:logback-classic:1.5.18")
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin { jvmToolchain(11) }
application { mainClass.set("top.ntutn.agent.bridge.MainKt") }
tasks.test { useJUnitPlatform() }
tasks.named<JavaExec>("run") { standardInput = System.`in` }

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
