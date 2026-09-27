import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm")
    id("org.jetbrains.compose") version "1.8.2"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.21"
}

repositories {
    mavenCentral()
    google()
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// UI bytecode stays compatible with JDK 11; packaging uses the build JVM's jpackage (JDK 17+).
kotlin { jvmToolchain(11) }
tasks.test { useJUnitPlatform() }

compose.desktop {
    application {
        mainClass = "top.ntutn.agent.bridge.desktop.DesktopMainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Deb)
            packageName = "Agent Bridge"
            packageVersion = "1.0.0"
            description = "本地 Agent 会话工作台"
            vendor = "Agent Bridge"
            modules("java.net.http", "jdk.unsupported")
            macOS { bundleID = "top.ntutn.agent.bridge.desktop" }
        }
    }
}
