plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.hellondk"
    compileSdk = 37
    // The NDK folder and cmake.dir come from local.properties; see docs/android-ndk-spike.md.
    defaultConfig {
        applicationId = "app.hellondk"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        ndk { abiFilters += (project.findProperty("spikeAbis")?.toString() ?: "arm64-v8a").split(",") }
        externalNativeBuild {
            cmake {
                // Termux has no static libc++, so the plugin's default (c++_static) cannot link here.
                arguments += "-DANDROID_STL=" + (project.findProperty("spikeStl") ?: "c++_shared")
            }
        }
    }
    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt") }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Spike only: signed with the debug key so the release APK installs.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    buildFeatures { compose = true }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.material3:material3")
}
