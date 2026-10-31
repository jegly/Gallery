plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.gal"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.gal"
        minSdk = 33
        targetSdk = 37
        // CI release builds pass these; local builds use the defaults.
        versionCode = providers.gradleProperty("appVersionCode").map(String::toInt).getOrElse(7)
        versionName = providers.gradleProperty("appVersionName").getOrElse("4.2.0")

        // meow meow meow meow
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    implementation(libs.guava)

    // Compose BOM
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.animation)
    implementation(libs.compose.foundation)
    implementation(libs.material3.adaptive.navigation.suite)
    debugImplementation(libs.compose.ui.tooling)

    // Activity / Hellcycle
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)

    // Navigation
    implementation(libs.navigation.compose)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // Coil — local image loading only, no network fetcher
    implementation(libs.coil.compose)
    implementation(libs.coil.video)

    // Media3 ExoPlayer — local video only
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)

    // EXIF
    implementation(libs.exifinterface)

    // Biometric
    implementation(libs.biometric)

    // DataStore
    implementation(libs.datastore.preferences)

    // Coroutines
    implementation(libs.coroutines.android)

    // Window / Foldable
    implementation(libs.window)

    // Material
    implementation(libs.material)

    // Core
    implementation(libs.android.image.cropper)
    implementation(libs.core.ktx)
    implementation(libs.splashscreen)
    implementation(libs.documentfile)
}
