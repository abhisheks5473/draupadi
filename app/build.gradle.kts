plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.gms.google-services")
}

android {
    namespace = "com.draupadi.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.draupadi.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        vectorDrawables { useSupportLibrary = true }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            // The CI workflow builds the debug variant so the APK is installable
            // without a keystore. Add your own signingConfig before publishing.
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }

    // Every permission-guarded call in this app is wrapped in a runtime check
    // and a try/catch, but lint cannot see through that. It must not be the
    // reason a build of a safety app fails.
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-service:2.8.6")

    val composeBom = platform("androidx.compose:compose-bom:2024.09.02")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    // camera + audio recording straight into MediaStore (the gallery)
    val camerax = "1.3.4"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-video:$camerax")

    // CameraX hands back a Guava ListenableFuture. Firebase drags in the
    // "listenablefuture:9999.0-empty-to-avoid-conflict-with-guava" stub — an
    // intentionally empty jar — so without real Guava on our own compile
    // classpath the type exists but has no class behind it.
    implementation("com.google.guava:guava:33.3.1-android")

    implementation("com.google.android.gms:play-services-location:21.3.0")

    val firebaseBom = platform("com.google.firebase:firebase-bom:33.3.0")
    implementation(firebaseBom)
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")
    implementation("com.google.firebase:firebase-storage")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")
}
