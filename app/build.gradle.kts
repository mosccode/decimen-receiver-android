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
        versionCode = 5
        versionName = "decimen-0.5.3-shell-5"
    }

    // No buildTypes block on purpose: the debug type already ships debuggable,
    // unminified and with the applicationId above, and CI assembles only that.

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.webkit:webkit:1.11.0")
}
