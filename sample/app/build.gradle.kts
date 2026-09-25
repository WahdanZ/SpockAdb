plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "spock.adb.sample"
    compileSdk = 36

    defaultConfig {
        applicationId = "spock.adb.sample"
        minSdk = 24
        targetSdk = 36
        versionCode = 7
        versionName = "1.2.0-sample"
    }

    buildTypes {
        debug {
            // Run-as, the debugger and Storage all need a debuggable build; debug is.
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    // The scripted Logcat scenario the plugin's docs point at, compiled in place rather than
    // copied, so there is one version of it. Some of its lines look like credentials, which is
    // fine here only because this app is never released.
    sourceSets["main"].java.srcDir("../../scripts/demo")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.navigation:navigation-fragment-ktx:2.8.5")
    implementation("androidx.navigation:navigation-ui-ktx:2.8.5")
    implementation("androidx.datastore:datastore-preferences:1.1.7")
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    // Composition tracing: what Recomposition Tracking counts. Debug-only in a real app.
    implementation("androidx.compose.runtime:runtime-tracing")
    // The native half of androidx.tracing.perfetto. Android Studio pushes it on demand; Spock
    // does not, so the app ships it.
    implementation("androidx.tracing:tracing-perfetto-binary:1.0.0")
}
