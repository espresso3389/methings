plugins {
    id("com.android.application") version "8.2.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
    id("com.google.gms.google-services") version "4.4.2" apply false
}

allprojects {
    repositories {
        google()
        mavenCentral()
        // RenderScript Toolkit is distributed via JitPack (AOSP mirror build).
        maven { url = uri("https://jitpack.io") }
    }
}
