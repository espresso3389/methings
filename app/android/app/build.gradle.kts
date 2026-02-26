plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.kapt")
    id("com.google.gms.google-services")
}

// Load .local_config/local.env from repo root (KEY=VALUE, one per line). Env vars take precedence.
val repoRootDir: File = rootProject.projectDir.parentFile.parentFile
val localEnv: Map<String, String> = run {
    val f = repoRootDir.resolve(".local_config/local.env")
    if (!f.exists()) return@run emptyMap()
    f.readLines()
        .map { it.trim() }
        .filter { it.isNotBlank() && !it.startsWith("#") && '=' in it }
        .associate { line ->
            val k = line.substringBefore('=').trim()
            val v = line.substringAfter('=').trim().removeSurrounding("\"")
            k to v
        }
}
fun localEnv(key: String): String = (System.getenv(key) ?: localEnv[key] ?: "").trim()

val defaultAppVersionName = "0.1.0"
val envVersionName = (System.getenv("METHINGS_VERSION_NAME") ?: "").trim()
val appVersionName = if (envVersionName.isNotBlank()) envVersionName else defaultAppVersionName
val appGitSha = (System.getenv("METHINGS_GIT_SHA") ?: "").trim().ifBlank {
    runCatching {
        val repoRoot = rootProject.projectDir.parentFile.parentFile
        val p = ProcessBuilder("git", "rev-parse", "--short=12", "HEAD")
            .directory(repoRoot)
            .redirectErrorStream(true)
            .start()
        p.inputStream.bufferedReader().use { it.readText().trim() }.ifBlank { "unknown" }
    }.getOrDefault("unknown")
}
val appRepoUrl = "https://github.com/espresso3389/methings"

android {
    namespace = "jp.espresso3389.methings"
    compileSdk = 34
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "jp.espresso3389.methings"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = appVersionName
        buildConfigField("String", "GIT_SHA", "\"$appGitSha\"")
        buildConfigField("String", "REPO_URL", "\"$appRepoUrl\"")
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"${localEnv("GOOGLE_WEB_CLIENT_ID")}\"")

        // Target only arm64 for now (Android 14+ devices). This also keeps the
        // bundled native deps (libusb/libuvc) consistent and smaller.
        ndk {
            abiFilters += setOf("arm64-v8a")
        }
    }

    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("ANDROID_KEYSTORE_PATH") ?: ""
            val keystorePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD") ?: ""
            val keyAlias = System.getenv("ANDROID_KEY_ALIAS") ?: ""
            val keyPassword = System.getenv("ANDROID_KEY_PASSWORD") ?: ""
            if (keystorePath.isNotBlank() && keystorePassword.isNotBlank() && keyAlias.isNotBlank() && keyPassword.isNotBlank()) {
                storeFile = file(keystorePath)
                storePassword = keystorePassword
                this.keyAlias = keyAlias
                this.keyPassword = keyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        buildConfig = true
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

val syncSystemAssets by tasks.registering(Copy::class) {
    val repoRoot = rootProject.projectDir.parentFile.parentFile
    val srcDir = repoRoot.resolve("user")
    val dstDir = projectDir.resolve("src/main/assets/system")
    from(srcDir) {
        include("docs/**", "examples/**", "lib/**")
    }
    into(dstDir)
    doFirst {
        dstDir.deleteRecursively()
    }
}

val syncUserDefaults by tasks.registering(Copy::class) {
    val repoRoot = rootProject.projectDir.parentFile.parentFile
    val srcDir = repoRoot.resolve("user")
    val dstDir = projectDir.resolve("src/main/assets/user_defaults")
    from(srcDir)
    // docs/, examples/, lib/ are read-only system assets (synced by syncSystemAssets)
    exclude("docs/**", "examples/**", "lib/**")
    into(dstDir)
    doFirst {
        dstDir.deleteRecursively()
    }
}

tasks.register("syncUserDefaultsOnBuild") {
    group = "build setup"
    description = "Explicitly refresh app/assets/user_defaults from repo/user."
    dependsOn(syncUserDefaults)
}

val isCiBuild = (System.getenv("CI") ?: "").trim().lowercase() in setOf("1", "true", "yes")
val updateFullLicensesOnBuild =
    isCiBuild ||
        ((System.getenv("METHINGS_UPDATE_FULL_LICENSES") ?: "").trim() == "1") ||
        ((findProperty("methings.updateFullLicenses") as? String)?.trim() == "1")

val verifyPythonRuntime by tasks.registering {
    val arch = "arm64-v8a"
    val jniLibsDir = projectDir.resolve("src/main/jniLibs/$arch")
    val assetsPyenvDir = projectDir.resolve("src/main/assets/pyenv")

    doLast {
        val requiredLibs = listOf(
            "libpython3.11.so",
            "libssl1.1.so",
            "libcrypto1.1.so",
            "libffi.so",
            "libsqlite3.so",
            "libmain.so",
        )
        val missingLibs = requiredLibs.filter { !jniLibsDir.resolve(it).exists() }
        val missingStdlib = !assetsPyenvDir.resolve("stdlib.zip").exists()

        if (missingLibs.isNotEmpty() || missingStdlib) {
            throw GradleException(
                buildString {
                    appendLine("Embedded Python runtime is missing from the Android project.")
                    if (missingLibs.isNotEmpty()) {
                        appendLine("Missing native libs under ${jniLibsDir}:")
                        missingLibs.forEach { appendLine("- $it") }
                    }
                    if (missingStdlib) {
                        appendLine("Missing assets under ${assetsPyenvDir}:")
                        appendLine("- stdlib.zip")
                    }
                    appendLine()
                    appendLine("Fix:")
                    appendLine("- If you already built a python-for-android dist, sync it:")
                    appendLine("  ./scripts/sync_p4a_dist.sh (DIST_NAME=methings ARCH=$arch)")
                    appendLine("- Otherwise build the dist (requires internet):")
                    appendLine("  SDK_DIR=... NDK_DIR=... ./scripts/build_p4a.sh (DIST_NAME=methings ARCH=$arch)")
                }
            )
        }
    }
}

val buildUsbLibs by tasks.registering(Exec::class) {
    val repoRoot = rootProject.projectDir.parentFile.parentFile
    val ndkDir =
        System.getenv("NDK_DIR")
            ?: System.getenv("ANDROID_NDK_ROOT")
            ?: System.getenv("ANDROID_NDK_HOME")
            ?: android.ndkDirectory.absolutePath
    if (ndkDir.isNullOrBlank()) {
        throw GradleException(
            "NDK_DIR is required to build libusb/libuvc. " +
                "Set ANDROID_NDK_ROOT or ANDROID_NDK_HOME."
        )
    }
    workingDir = repoRoot
    environment("NDK_DIR", ndkDir)
    environment("DROPBEAR_INCLUDE_CLIENT_TOOLS", "1")
    commandLine(
        "bash",
        "-lc",
        "./scripts/build_dropbear.sh && ./scripts/build_libusb_android.sh && ./scripts/build_libuvc_android.sh && ./scripts/build_methingssh_android.sh && ./scripts/build_methingspy_android.sh"
    )
}


val fetchNodeRuntime by tasks.registering(Exec::class) {
    val repoRoot = rootProject.projectDir.parentFile.parentFile
    workingDir = repoRoot
    // Opt-in: fetches Termux-built Node.js + npm and stages them into jniLibs/assets.
    // Enable with: METHINGS_FETCH_NODE_RUNTIME=1
    val enabled = (System.getenv("METHINGS_FETCH_NODE_RUNTIME") ?: "").trim() == "1"
    commandLine(
        "bash",
        "-lc",
        if (enabled) "./scripts/fetch_termux_node_android.sh" else "echo 'fetchNodeRuntime: disabled (set METHINGS_FETCH_NODE_RUNTIME=1 to enable)'"
    )
}

val generateDependencyInventory by tasks.registering(Exec::class) {
    val repoRoot = rootProject.projectDir.parentFile.parentFile
    workingDir = repoRoot
    val cmd =
        if (updateFullLicensesOnBuild) {
            "python3 ./scripts/generate_dependency_inventory.py --output ./licenses/dependency_inventory.json --licenses-output ./licenses/full_licenses.json"
        } else {
            "python3 ./scripts/generate_dependency_inventory.py --output ./licenses/dependency_inventory.json"
        }
    commandLine(
        "bash",
        "-lc",
        cmd
    )
    doFirst {
        logger.lifecycle(
            "generateDependencyInventory: updateFullLicensesOnBuild=${updateFullLicensesOnBuild} (CI=${isCiBuild})"
        )
    }
}

val syncDependencyInventoryAsset by tasks.registering {
    val repoRoot = rootProject.projectDir.parentFile.parentFile
    dependsOn(generateDependencyInventory)
    doLast {
        val src = repoRoot.resolve("licenses/dependency_inventory.json")
        if (!src.exists()) {
            throw GradleException("Missing generated dependency inventory: ${src.absolutePath}")
        }
        val dst = projectDir.resolve("src/main/assets/www/licenses/dependency_inventory.json")
        dst.parentFile.mkdirs()
        src.copyTo(dst, overwrite = true)
        val fullSrc = repoRoot.resolve("licenses/full_licenses.json")
        if (!fullSrc.exists()) {
            throw GradleException("Missing generated full licenses: ${fullSrc.absolutePath}")
        }
        val fullDst = projectDir.resolve("src/main/assets/www/licenses/full_licenses.json")
        fullDst.parentFile.mkdirs()
        fullSrc.copyTo(fullDst, overwrite = true)
    }
}

val syncGoogleServicesJson by tasks.registering {
    val dst = projectDir.resolve("google-services.json")
    doLast {
        // CI: decode from GOOGLE_SERVICES_JSON_BASE64 env var
        val b64 = (System.getenv("GOOGLE_SERVICES_JSON_BASE64") ?: "").trim()
        if (b64.isNotBlank()) {
            dst.writeBytes(org.apache.commons.codec.binary.Base64().decode(b64))
            logger.lifecycle("syncGoogleServicesJson: decoded from GOOGLE_SERVICES_JSON_BASE64")
            return@doLast
        }
        // Local: copy from .local_config/
        val src = repoRootDir.resolve(".local_config/google-services.json")
        if (src.exists()) {
            src.copyTo(dst, overwrite = true)
            logger.lifecycle("syncGoogleServicesJson: copied from .local_config/")
            return@doLast
        }
        if (!dst.exists()) {
            throw GradleException(
                "google-services.json not found. Either:\n" +
                    "- Place it in .local_config/google-services.json (local dev)\n" +
                    "- Set GOOGLE_SERVICES_JSON_BASE64 env var (CI)"
            )
        }
    }
}

tasks.named("preBuild") {
    dependsOn(syncGoogleServicesJson, syncServerAssets, syncSystemAssets, syncUserDefaults, verifyPythonRuntime, buildUsbLibs, fetchNodeRuntime, syncDependencyInventoryAsset)
}

// `jniLibs/` and `assets/bin/<abi>/` are generated by build scripts, and stale files here can
// break runtime behavior (e.g., missing/old `.so` in the app sandbox). Ensure `clean` purges them.
tasks.named<Delete>("clean") {
    val jniRoot = projectDir.resolve("src/main/jniLibs")
    // Keep python-for-android runtime libs (libpython*, libssl*, etc.) since they may be synced
    // from an existing p4a dist. Only purge generated project libs.
    delete(
        fileTree(jniRoot) {
            include("**/libusb*.so")
            include("**/libuvc.so")
            include("**/libdropbear.so")
            include("**/libdropbearkey.so")
        }
    )
    listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64").forEach { abi ->
        delete(projectDir.resolve("src/main/assets/bin/$abi"))
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.lifecycle:lifecycle-service:2.7.0")
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation("com.google.android.material:material:1.11.0")
    implementation("com.google.android.gms:play-services-nearby:19.3.0")
    // CameraX for camera capture/preview endpoints.
    implementation("androidx.camera:camera-camera2:1.3.2")
    implementation("androidx.camera:camera-lifecycle:1.3.2")
    implementation("androidx.camera:camera-core:1.3.2")
    implementation("androidx.camera:camera-video:1.3.2")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("org.nanohttpd:nanohttpd-websocket:2.3.1")
    implementation("com.github.mik3y:usb-serial-for-android:3.9.0")
    // Small CPU image processing intrinsics (blur, convolution, etc.) without pulling in OpenCV.
    implementation("com.github.android:renderscript-intrinsics-replacement-toolkit:344be3f6bf03fb")
    // On-device inference runtime. We keep Python for orchestration and use Android-side TFLite for performance.
    implementation("org.tensorflow:tensorflow-lite:2.17.0")
    implementation("org.tensorflow:tensorflow-lite-gpu:2.17.0")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
    implementation("androidx.browser:browser:1.7.0")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("com.google.zxing:core:3.5.3")
    // Firebase Cloud Messaging
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-messaging")
    // WebRTC DataChannel (me.me P2P transport)
    implementation("io.getstream:stream-webrtc-android:1.3.7")
    implementation("org.java-websocket:Java-WebSocket:1.5.7")
    // In-process JavaScript engine for agent run_js tool
    implementation("io.github.dokar3:quickjs-kt:1.0.0-alpha13")
}
