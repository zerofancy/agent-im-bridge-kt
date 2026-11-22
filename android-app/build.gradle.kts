import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.keyring)
}

keyring {
    secret("android_store_password", "signStorePassword", "SIGN_STORE_PASSWORD")
    secret("android_key_password", "signKeyPassword", "SIGN_KEY_PASSWORD")
}

val localSigningProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}

fun signingProperty(name: String): String? =
    providers.gradleProperty(name).orNull ?: localSigningProperties.getProperty(name)

val keyringSigningEnabled = when (val value = signingProperty("keyringSigning") ?: "true") {
    "true" -> {
        true
    }
    "false" -> {
        false
    }
    else -> {
        throw GradleException("keyringSigning must be true or false, received '$value'.")
    }
}

// AGP retains passwords in its model; never serialize that model into the configuration cache.
@Suppress("DEPRECATION")
val configurationCacheRequested = gradle.startParameter.isConfigurationCacheRequested
if (keyringSigningEnabled && configurationCacheRequested) {
    throw GradleException("Keyring signing requires --no-configuration-cache to avoid caching passwords.")
}

fun requiredSigningProperty(name: String): String =
    signingProperty(name)?.takeIf { it.isNotBlank() }
        ?: throw GradleException(
            "Set $name in android-app/local.properties or with -P$name. " +
                "See docs/android-signing.md; use -PkeyringSigning=false to initialize credentials."
        )

fun signingPassword(name: String): String =
    keyring.secrets[name]?.takeUnless { it == "<not set>" || it.isEmpty() }
        ?: throw GradleException("Signing secret '$name' is unavailable. See docs/android-signing.md.")

repositories {
    google()
    mavenCentral()
}

android {
    namespace = "top.ntutn.agent.bridge.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "top.ntutn.agent.bridge.android"
        minSdk = 24
        targetSdk = 36
        versionCode = 2
        versionName = "1.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    if (keyringSigningEnabled) {
        val keystore = rootProject.file(requiredSigningProperty("keyringStoreFile"))
        if (!keystore.isFile) {
            throw GradleException("Signing keystore does not exist: $keystore")
        }
        signingConfigs.create("keyringRelease") {
            storeFile = keystore
            keyAlias = requiredSigningProperty("keyringKeyAlias")
            storePassword = signingPassword("android_store_password")
            keyPassword = signingPassword("android_key_password")
        }
    }

    buildTypes {
        release {
            if (keyringSigningEnabled) {
                signingConfig = signingConfigs.getByName("keyringRelease")
            }
            optimization { enable = true }
        }
    }

    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

val releaseSigningConfig = android.buildTypes.getByName("release").signingConfig
val verifyReleaseSigning by tasks.registering {
    group = "verification"
    description = "Verify the release private key and print the signing certificate SHA-256."
    notCompatibleWithConfigurationCache("Reads local signing credentials")
    doLast {
        val config = releaseSigningConfig
            ?: throw GradleException("Release signing is disabled; set keyringSigning=true.")
        val keystore = config.storeFile ?: throw GradleException("Signing keystore is not configured.")
        val alias = config.keyAlias ?: throw GradleException("Signing key alias is not configured.")
        val storePassword = config.storePassword?.toCharArray()
            ?: throw GradleException("Keystore password is not configured.")
        val keyPassword = config.keyPassword?.toCharArray()
            ?: throw GradleException("Private key password is not configured.")
        try {
            val store = KeyStore.getInstance(keystore, storePassword)
            if (store.getKey(alias, keyPassword) !is PrivateKey) {
                throw GradleException("Signing alias does not contain a private key.")
            }
            if (store.getCertificateChain(alias).isNullOrEmpty()) {
                throw GradleException("Signing alias does not contain a certificate chain.")
            }
            val digest = MessageDigest.getInstance("SHA-256").digest(store.getCertificate(alias).encoded)
            logger.lifecycle("Release certificate SHA-256: " + digest.joinToString(":") { "%02X".format(it) })
        } finally {
            storePassword.fill('\u0000')
            keyPassword.fill('\u0000')
        }
    }
}

// Never let an explicitly disabled keyring silently produce an unsigned release artifact.
tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    dependsOn(verifyReleaseSigning)
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.compose.material)
    implementation(libs.androidx.compose.preview)
    implementation(libs.gson)
    implementation(libs.okhttp)

    testImplementation(libs.junit4)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.okhttp.tls)
    androidTestImplementation(libs.androidx.compose.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    debugImplementation(libs.androidx.compose.tooling)
    debugImplementation(libs.androidx.compose.test.manifest)
}
