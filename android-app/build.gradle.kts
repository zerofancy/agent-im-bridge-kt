plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

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
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.compose.material)
    implementation(libs.gson)
    implementation(libs.okhttp)

    testImplementation(libs.junit4)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.okhttp.tls)
    androidTestImplementation(libs.androidx.compose.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    debugImplementation(libs.androidx.compose.test.manifest)
}
