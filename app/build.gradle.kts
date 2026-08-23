import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Load signing credentials from local.properties (never committed to git).
val localProps = Properties().also { props ->
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { props.load(it) }
}

android {
    namespace = "com.pgratz.artouchpad"
    compileSdk = 35

    signingConfigs {
        create("release") {
            storeFile = file(localProps.getProperty("KEYSTORE_PATH", "release.jks"))
            storePassword = localProps.getProperty("KEYSTORE_PASSWORD", "")
            keyAlias = localProps.getProperty("KEY_ALIAS", "artouchpad")
            keyPassword = localProps.getProperty("KEY_PASSWORD", "")
        }
    }

    defaultConfig {
        applicationId = "com.pgratz.artouchpad"
        // minSdk 34 (Android 14): required for WindowManager.currentWindowMetrics and
        // the DisplayManager APIs used to detect the glasses as a secondary display.
        minSdk = 34
        targetSdk = 35
        versionCode = 3
        versionName = "1.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            // arm64-v8a only: Pixel 10 and Viture XR Pro are both 64-bit ARM devices.
            // Omitting x86/x86_64 keeps the APK small and avoids cross-compiling uinput_jni.
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        compose = true
        // aidl: generates IMouseService Java stubs from IMouseService.aidl, used for
        // IPC between the app process and the Shizuku UserService (MouseService).
        aidl = true
    }

    lint {
        // BlockedPrivateApi: we deliberately access InputManagerGlobal.injectInputEvent and
        // InputEvent.setDisplayId via reflection, bypassed at runtime by HiddenApiBypass.
        disable += "BlockedPrivateApi"
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.material)
    // Shizuku: lets the app bind MouseService as a shell-uid UserService so it can
    // access /dev/uinput and inject input events without root.
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    // HiddenApiBypass: allows reflection access to InputManagerGlobal.injectInputEvent
    // and InputEvent.setDisplayId, which are restricted hidden APIs on Android 9+.
    implementation(libs.hiddenapibypass)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
