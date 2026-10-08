plugins {
    id("com.android.application")
}

android {
    namespace = "net.tare.decimenrx"
    compileSdk = 34

    defaultConfig {
        applicationId = "net.tare.decimenrx"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "decimen-0.5.3-shell-1"
    }

    buildTypes {
        // The CI ships the debug build on purpose: it is signed with the
        // generated debug key so the APK installs by tapping it, and no
        // keystore secret ever has to live in this repository.
        get("debug") {
            applicationIdSuffix = ""
            isDebuggable = true
        }
        get("release") {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module")
    }
}

dependencies {
    implementation("androidx.webkit:webkit:1.11.0")
}
