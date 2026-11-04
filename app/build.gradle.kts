import javax.inject.Inject
import org.gradle.process.ExecOperations

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

// The editor engine is built for these ABIs (see "Editor engine" below).
val editorCrateDir: File = rootProject.file("native/editor")
val editorAbis: List<String> = listOf("arm64-v8a")

android {
    namespace = "com.gal"
    compileSdk = 37
    // the editor engine (native/editor) is built with this NDK
    ndkVersion = "30.0.16248370"

    defaultConfig {
        applicationId = "com.gal"
        minSdk = 33
        targetSdk = 37
        // CI release builds pass these; local builds use the defaults.
        versionCode = providers.gradleProperty("appVersionCode").map(String::toInt).getOrElse(7)
        versionName = providers.gradleProperty("appVersionName").getOrElse("4.2.0")

        // meow meow meow meow
        ndk {
            abiFilters += editorAbis
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

// ── Editor engine ────────────────────────────────────────────────────────────
// native/editor is a Rust crate (Grainhouse's editing library behind a JNI bridge); cargo-ndk builds
// libgal_editor.so for each ABI in abiFilters and the variant packages it like any other jniLib.

/** rustup's cargo (Android Studio may not see rustup's PATH), else cargo on PATH; `CARGO` overrides. */
val cargoExecutable: String = providers.environmentVariable("CARGO").orNull
    ?: File(System.getProperty("user.home"), ".cargo/bin/cargo").takeIf { it.isFile }?.absolutePath
    ?: "cargo"

abstract class CargoNdkBuild : DefaultTask() {
    @get:Inject abstract val execOperations: ExecOperations
    @get:Input abstract val cargo: Property<String>
    @get:Input abstract val crateDir: Property<String>
    @get:Input abstract val ndkDir: Property<String>
    @get:Input abstract val abis: ListProperty<String>
    @get:Input abstract val apiLevel: Property<Int>
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun build() {
        val crate = File(crateDir.get())
        // --locked once the lock file is committed, so CI builds exactly the reviewed dependencies
        val locked = if (File(crate, "Cargo.lock").isFile) listOf("--locked") else emptyList()
        execOperations.exec {
            workingDir(crate)
            environment("ANDROID_NDK_HOME", ndkDir.get())
            // no build paths in the library, so the same commit builds the same bytes anywhere
            environment("CARGO_ENCODED_RUSTFLAGS", "--remap-path-prefix=${crate.absolutePath}=/gal-editor")
            commandLine(
                listOf(cargo.get(), "ndk") + abis.get().flatMap { listOf("-t", it) } +
                    listOf("--platform", apiLevel.get().toString(), "-o", outputDir.get().asFile.absolutePath,
                        "build", "--release") + locked,
            )
        }
    }
}

androidComponents {
    onVariants { variant ->
        val name = variant.name.replaceFirstChar { it.uppercase() }
        val cargoBuild = tasks.register<CargoNdkBuild>("cargoBuildEditor$name") {
            group = "rust"
            description = "Builds libgal_editor.so for $editorAbis"
            cargo.set(cargoExecutable)
            crateDir.set(editorCrateDir.absolutePath)
            ndkDir.set(androidComponents.sdkComponents.ndkDirectory.map { it.asFile.absolutePath })
            abis.set(editorAbis)
            apiLevel.set(33)
            // cargo tracks its own inputs
            outputs.upToDateWhen { false }
        }
        variant.sources.jniLibs?.addGeneratedSourceDirectory(cargoBuild, CargoNdkBuild::outputDir)
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
    implementation(libs.core.ktx)
    implementation(libs.splashscreen)
    implementation(libs.documentfile)
}
