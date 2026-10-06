import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm")
    alias(libs.plugins.compose)
    alias(libs.plugins.kotlin.compose)
}

repositories {
    mavenCentral()
    google()
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material)
    implementation(libs.compose.components.resources)
    implementation(libs.bundles.multiplatform.markdown.renderer)
    implementation(libs.coil.network.okhttp)
    implementation(libs.coil.svg)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.gson)
    implementation(libs.okhttp)
    testImplementation(libs.kotlin.test.junit5)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// Compilation and the bundled desktop runtime both use JDK 21.
kotlin { jvmToolchain(21) }
tasks.test { useJUnitPlatform() }

// Share the source artwork with README without maintaining a second PNG copy.
val prepareBrandingResources by tasks.registering(Sync::class) {
    from(rootProject.file("assets/branding/agent-bridge-app-icon.png")) {
        into("drawable")
        rename { "agent_bridge_app_icon.png" }
    }
    into(layout.buildDirectory.dir("generated/brandingResources"))
}

compose.resources {
    packageOfResClass = "top.ntutn.agent.bridge.desktop.resources"
    customDirectory(
        sourceSetName = "main",
        directoryProvider = layout.dir(prepareBrandingResources.map { it.destinationDir }),
    )
}

compose.desktop {
    application {
        javaHome = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) }
            .get().metadata.installationPath.asFile.absolutePath
        mainClass = "top.ntutn.agent.bridge.desktop.DesktopMainKt"
        buildTypes.release.proguard {
            configurationFiles.from(project.file("proguard-rules.pro"))
        }
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Deb)
            packageName = "Agent Bridge"
            packageVersion = "1.0.0"
            description = "本地 Agent 会话工作台"
            vendor = "Agent Bridge"
            modules("java.net.http", "jdk.unsupported")
            macOS {
                bundleID = "top.ntutn.agent.bridge.desktop"
                iconFile.set(rootProject.file("assets/branding/agent-bridge-app-icon.icns"))
            }
            linux {
                iconFile.set(rootProject.file("assets/branding/agent-bridge-app-icon.png"))
            }
        }
    }
}
