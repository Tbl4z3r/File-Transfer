plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "local.filetransfer"
    compileSdk = 36
    buildToolsVersion = "35.0.0"
    defaultConfig { applicationId = "local.filetransfer"; minSdk = 28; targetSdk = 36; versionCode = 4; versionName = "0.1.3-dev" }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.10.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("com.google.zxing:core:3.5.4")
    implementation("com.squareup.okhttp3:okhttp:5.4.0")
    implementation("org.json:json:20250517")
    implementation("com.google.code.gson:gson:2.13.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver3:5.4.0")
    testImplementation("com.squareup.okhttp3:okhttp-tls:5.4.0")
    testImplementation("org.bouncycastle:bcpkix-jdk18on:1.81")
}
dependencyLocking { lockAllConfigurations() }

// A supplied core is an integration input, and must run even if its path is unchanged.
tasks.withType<Test>().configureEach {
    inputs.property("ftCoreBinary", providers.environmentVariable("FT_CORE_BINARY").orElse(""))
    outputs.upToDateWhen { System.getenv("FT_CORE_BINARY").isNullOrBlank() }
}
