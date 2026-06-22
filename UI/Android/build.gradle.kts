import com.android.build.gradle.internal.tasks.factory.dependsOn
import org.gradle.api.tasks.bundling.Zip

plugins {
    id("com.android.application") version "8.11.0"
    id("org.jetbrains.kotlin.android") version "2.1.20"
}

var buildDir = layout.buildDirectory.get()
var cacheDir = System.getenv("LADYBIRD_CACHE_DIR") ?: "$buildDir/caches"
var sourceDir = layout.projectDirectory.dir("../../").toString()

val ladybirdAssetsDir = layout.buildDirectory.dir("generated/ladybird-assets")
val packageLadybirdAssets = tasks.register<Zip>("packageLadybirdAssets") {
    archiveFileName.set("ladybird-assets.zip")
    destinationDirectory.set(ladybirdAssetsDir)
    isReproducibleFileOrder = true
    isPreserveFileTimestamps = false

    from("$sourceDir/Base/res/ladybird") { into("res/ladybird") }
    from("$sourceDir/Base/res/fonts") { into("res/fonts") }
    from("$sourceDir/Base/res/icons") { into("res/icons") }
    from("$sourceDir/Base/res/themes") { into("res/themes") }
    // Brave-derived ad-block filter lists + uBO scriptlet resources, unpacked
    // into <resource_root>/res/adblock and loaded by RequestServer at startup.
    from("$sourceDir/Base/res/adblock") { into("res/adblock") }
}

android {
    namespace = "org.serenityos.ladybird"
    compileSdk = 35
    // FIXME: Replace the NDK version to a stable one (this is r29 beta 2)
    ndkVersion = "29.0.13599879"

    defaultConfig {
        applicationId = "org.serenityos.ladybird"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++23"
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DLADYBIRD_CACHE_DIR=$cacheDir",
                    "-DVCPKG_ROOT=$sourceDir/Build/vcpkg",
                    "-DVCPKG_TARGET_ANDROID=ON"
                    // NB: don't override PKG_CONFIG_EXECUTABLE here. Pointing it
                    // at Build/host-tools-build/.../pkgconf requires a prior host
                    // tools build that nothing in this project actually performs
                    // (it only worked locally due to leftover build state), so on
                    // a clean checkout the Android configure fails finding it.
                    // Let CMake find the system pkg-config instead (matches
                    // upstream); for ANDROID it's only needed by find_package and
                    // the sole pkg_check_modules call is skipped.
                )
            }
        }
        ndk {
            // Specifies the ABI configurations of your native
            // libraries Gradle should build and package with your app.
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isDebuggable = false
            isJniDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("debug")
            ndk {
                debugSymbolLevel = "NONE"
            }
            externalNativeBuild {
                cmake {
                    arguments += listOf(
                        "-DCMAKE_BUILD_TYPE=MinSizeRel",
                        "-DCMAKE_INTERPROCEDURAL_OPTIMIZATION=ON"
                    )
                    cppFlags += listOf("-Oz")
                }
            }
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
    externalNativeBuild {
        cmake {
            path = file("../../CMakeLists.txt")
            version = "3.23.0+"
        }
    }

    buildFeatures {
        viewBinding = true
        prefab = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    sourceSets {
        getByName("main").assets.setSrcDirs(listOf(ladybirdAssetsDir))
    }
}

tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }.configureEach {
    dependsOn(packageLadybirdAssets)
}

tasks.matching { it.name.contains("lint", ignoreCase = true) }.configureEach {
    dependsOn(packageLadybirdAssets)
}

// A transitive dependency drags in a newer kotlin-stdlib than our Kotlin plugin
// (2.1.x) can read; pin it so the metadata versions stay compatible.
configurations.all {
    resolutionStrategy {
        force("org.jetbrains.kotlin:kotlin-stdlib:2.1.20")
        force("org.jetbrains.kotlin:kotlin-stdlib-jdk8:2.1.20")
        force("org.jetbrains.kotlin:kotlin-stdlib-jdk7:2.1.20")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    // Tor: Guardian Project's prebuilt tor (ships libtor.so for arm64 + TorService)
    // plus jtorctl for the control connection it uses internally.
    implementation("info.guardianproject:tor-android:0.4.8.22")
    implementation("info.guardianproject:jtorctl:0.4.5.7")
    implementation("androidx.localbroadcastmanager:localbroadcastmanager:1.1.0")
    // I2P: embedded PurpleI2P i2pd, bundled as src/main/jniLibs/arm64-v8a/
    // libi2pd.so and driven through the vendored org.purplei2p.i2pd.I2PD_JNI
    // class. No external app or Maven dependency — the router runs in-process
    // and exposes an HTTP proxy on 127.0.0.1:4444 (see I2pLauncher).
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.ext:junit-ktx:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}
