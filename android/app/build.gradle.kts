plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "cn.studybridge.mobile"
    compileSdk = 35
    defaultConfig {
        applicationId = "cn.studybridge.mobile"
        minSdk = 26
        targetSdk = 35
        versionCode = 19
        versionName = "0.9.0-preview"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true }
    signingConfigs {
        create("release") {
            storeFile = System.getenv("RAIN_SIGNING_KEYSTORE")?.let { file(it) }
            storePassword = System.getenv("RAIN_SIGNING_STORE_PASSWORD")
            keyAlias = System.getenv("RAIN_SIGNING_KEY_ALIAS")
            keyPassword = System.getenv("RAIN_SIGNING_KEY_PASSWORD")
            storeType = "PKCS12"
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
// Never fall back to a fresh debug certificate for an installable release.
tasks.configureEach {
    if (name == "validateSigningRelease" || name == "packageRelease") {
        doFirst {
            check(listOf("RAIN_SIGNING_KEYSTORE", "RAIN_SIGNING_STORE_PASSWORD", "RAIN_SIGNING_KEY_ALIAS", "RAIN_SIGNING_KEY_PASSWORD").all { !System.getenv(it).isNullOrBlank() }) {
                "Fixed release signing is required. Configure ANDROID_RELEASE_SIGNING; debug-key releases are disabled."
            }
        }
    }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.nimbusds:nimbus-jose-jwt:9.47")
    implementation("com.google.errorprone:error_prone_annotations:2.28.0")
    implementation("io.noties.markwon:core:4.6.2")
    implementation("io.noties.markwon:ext-tables:4.6.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("com.squareup.okhttp3:okhttp-tls:4.12.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.04.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
