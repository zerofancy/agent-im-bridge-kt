import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm")
    id("org.jetbrains.compose") version "1.11.1"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.0"
}

repositories {
    mavenCentral()
    google()
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material)
    implementation("com.mikepenz:multiplatform-markdown-renderer-m2:0.43.0")
    implementation("com.mikepenz:multiplatform-markdown-renderer-code:0.43.0")
    implementation("com.mikepenz:multiplatform-markdown-renderer-coil3:0.43.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.5.0")
    implementation("io.coil-kt.coil3:coil-svg:3.5.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Compilation and the bundled desktop runtime both use JDK 25.
kotlin { jvmToolchain(25) }
tasks.test { useJUnitPlatform() }

compose.desktop {
    application {
        javaHome = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) }
            .get().metadata.installationPath.asFile.absolutePath
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
