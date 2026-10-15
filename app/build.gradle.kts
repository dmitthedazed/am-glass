plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.dimka.amglass"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.dimka.amglass"
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
    }

    // Module R ids live inside Apple Music's process next to its own 0x7f ids;
    // a separate package id keeps Compose's view tags from colliding with them.
    androidResources {
        additionalParameters += listOf("--allow-reserved-package-id", "--package-id", "0x6e")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    buildFeatures { compose = true }
}

dependencies {
    compileOnly("de.robv.android.xposed:api:82")
    // Kyant0/AndroidLiquidGlass (kmp branch) — the library and the catalog's LiquidBottomTabs.
    implementation("io.github.kyant0:backdrop:2.0.1")
    implementation("io.github.kyant0:shapes:1.2.1")
    implementation("org.jetbrains.compose.foundation:foundation:1.12.0")
    implementation("org.jetbrains.compose.ui:ui:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime:2.9.4")
    implementation("androidx.savedstate:savedstate:1.3.3")
}
