import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// The native payload (engine helpers + JNI bridge + resources) produced by tools/build_native.py.
// Pass -Pladybird.skipNative=true to build the app without it (requirement BLD-007).
val skipNative = providers.gradleProperty("ladybird.skipNative").map { it.toBoolean() }.getOrElse(false)
val nativeStageDir = file(providers.gradleProperty("ladybird.nativeStage").getOrElse("${rootDir.parentFile}/build/native-stage"))
val abis = providers.gradleProperty("ladybird.abis").getOrElse("arm64-v8a").split(',').map { it.trim() }

val generatedAssets = layout.buildDirectory.dir("generated/ladybird-assets")

val verifyNativeStage by tasks.registering(Exec::class) {
    description = "Checks that the native payload is complete (requirement BLD-006)."
    enabled = !skipNative
    workingDir = rootDir.parentFile
    commandLine(listOf("python3", "tools/build_native.py", "verify-stage", "--stage-dir", nativeStageDir.absolutePath, "--abis", abis.joinToString(",")))
}

val packageLadybirdResources by tasks.registering(Zip::class) {
    description = "Packs Ladybird's runtime resources into an asset (requirement ARCH-008)."
    enabled = !skipNative
    dependsOn(verifyNativeStage)
    from(File(nativeStageDir, "resources"))
    archiveFileName.set("ladybird-resources.zip")
    destinationDirectory.set(generatedAssets.map { it.dir("engine") })
    isReproducibleFileOrder = true
    isPreserveFileTimestamps = false
}

android {
    namespace = "io.github.junkers4.ladybird"
    compileSdk = 35
    // Only used by AGP to strip the prebuilt native libraries.
    ndkVersion = "29.0.13599879"

    defaultConfig {
        applicationId = "io.github.junkers4.ladybird"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += abis }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("boolean", "HAS_ENGINE", (!skipNative).toString())
    }

    buildTypes {
        release {
            // Requirement BLD-008. CI signs release builds with the debug key; real releases use their own key.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    packaging {
        jniLibs {
            // Helper processes are executables packaged as lib*.so: they must be extracted to the native
            // library directory so they can be exec()'d (requirement ARCH-001).
            useLegacyPackaging = true
        }
    }

    sourceSets {
        getByName("main") {
            if (!skipNative) {
                jniLibs.srcDir(File(nativeStageDir, "jniLibs"))
                assets.srcDir(generatedAssets)
            }
        }
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
        checkReleaseBuilds = false
        disable += setOf("GradleDependency", "NewerVersionAvailable", "OldTargetApi")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

tasks.matching { it.name.startsWith("merge") && (it.name.endsWith("Assets") || it.name.endsWith("JniLibFolders")) }.configureEach {
    dependsOn(packageLadybirdResources)
}
tasks.matching { it.name.startsWith("lint") || it.name.startsWith("generate") && it.name.endsWith("LintModel") }.configureEach {
    dependsOn(packageLadybirdResources)
}

// Requirement SEC-001: only these dependencies may be added (no analytics, crash reporting, ads or Play Services).
dependencies {
    implementation(project(":core"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("com.google.android.material:material:1.12.0")

    testImplementation("junit:junit:4.13.2")
}
