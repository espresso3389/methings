plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.kapt")
}

android {
    namespace = "jp.espresso3389.kugutz"
    compileSdk = 34
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "jp.espresso3389.kugutz"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        viewBinding = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    androidResources {
        ignoreAssetsPattern = ""
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

val syncServerAssets by tasks.registering(Copy::class) {
    val repoRoot = rootProject.projectDir.parentFile.parentFile
    val srcDir = repoRoot.resolve("server")
    val dstDir = projectDir.resolve("src/main/assets/server")
    from(srcDir)
    into(dstDir)
    doFirst {
        dstDir.deleteRecursively()
    }
}

val buildUsbLibs by tasks.registering(Exec::class) {
    val repoRoot = rootProject.projectDir.parentFile.parentFile
    val ndkDir = android.ndkDirectory?.absolutePath
        ?: System.getenv("ANDROID_NDK_ROOT")
        ?: System.getenv("ANDROID_NDK_HOME")
        ?: System.getenv("NDK_DIR")
    if (ndkDir.isNullOrBlank()) {
        throw GradleException(
            "NDK_DIR is required to build libusb/libuvc. " +
                "Set ANDROID_NDK_ROOT or ANDROID_NDK_HOME."
        )
    }
    workingDir = repoRoot
    environment("NDK_DIR", ndkDir)
    commandLine(
        "bash",
        "-lc",
        "./scripts/build_dropbear.sh && ./scripts/build_libusb_android.sh && ./scripts/build_libuvc_android.sh && ./scripts/build_kugutzsh_android.sh && ./scripts/build_kugutzpy_android.sh"
    )
}

tasks.named("preBuild") {
    dependsOn(syncServerAssets, buildUsbLibs)
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("com.google.android.material:material:1.11.0")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")
}
