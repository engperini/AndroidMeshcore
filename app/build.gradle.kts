plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.engperini.meshcore"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.engperini.meshcore"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
            }
        }
    }

    // Public, test-only key committed to the repo on purpose (as in MeshSDR):
    // every build, local or CI, gets the same signature, so new APKs install
    // over the previous one and keep the node identity. MESHCORE_KEYSTORE_*
    // can point a fork at its own key.
    val keystoreFile = System.getenv("MESHCORE_KEYSTORE_FILE")?.let { file(it) } ?: file("meshcore-debug.jks")
    val stableKey = keystoreFile.exists()
    signingConfigs {
        create("stable") {
            storeFile = keystoreFile
            storePassword = System.getenv("MESHCORE_KEYSTORE_PASSWORD") ?: "android"
            keyAlias = System.getenv("MESHCORE_KEY_ALIAS") ?: "androiddebugkey"
            keyPassword = System.getenv("MESHCORE_KEY_PASSWORD") ?: "android"
        }
    }

    buildTypes {
        debug {
            if (stableKey) signingConfig = signingConfigs.getByName("stable")
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName(if (stableKey) "stable" else "debug")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            // Two-node end-to-end test against the real firmware (tools/hosttest).
            it.systemProperty("hostNode", rootProject.file("tools/hosttest/build/host_node").absolutePath)
            // Optional Maven mirror for Robolectric's Android jars (screenshot tests).
            System.getenv("ROBOLECTRIC_REPO_URL")?.let { url -> it.systemProperty("robolectric.dependency.repo.url", url) }
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // Map (OpenStreetMap, no API key) and QR codes (meshcore:// links).
    implementation("org.osmdroid:osmdroid-android:6.1.20")
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
