plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.nzeus.nvg"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.nzeus.nvg"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "2.0.0"
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    signingConfigs {
        create("release") {
            // Populated by CI from secrets. Local builds fall back to debug.
            val storeFileProp = findProperty("RELEASE_STORE_FILE") as String?
            if (storeFileProp != null) {
                storeFile = file(storeFileProp)
                storePassword = findProperty("RELEASE_STORE_PASSWORD") as String?
                keyAlias = findProperty("RELEASE_KEY_ALIAS") as String?
                keyPassword = findProperty("RELEASE_KEY_PASSWORD") as String?
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            val storeFileProp = findProperty("RELEASE_STORE_FILE") as String?
            signingConfig = if (storeFileProp != null) signingConfigs.getByName("release")
                            else signingConfigs.getByName("debug")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { viewBinding = true }

    packaging {
        resources.excludes += setOf(
            "META-INF/AL2.0", "META-INF/LGPL2.1",
            "META-INF/DEPENDENCIES", "META-INF/LICENSE*"
        )
        jniLibs.useLegacyPackaging = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.4")
    implementation("com.google.android.material:material:1.12.0")

    // Camera2 directly (we need manual ISO/exposure — CameraX abstracts that away)
    implementation("androidx.camera:camera-core:1.3.4")

    // TensorFlow Lite + NNAPI delegate for Tensor G5 TPU
    implementation("org.tensorflow:tensorflow-lite:2.16.1")
    implementation("org.tensorflow:tensorflow-lite-gpu:2.16.1")
    implementation("org.tensorflow:tensorflow-lite-gpu-delegate-plugin:0.4.4")
    implementation("org.tensorflow:tensorflow-lite-support:0.4.4")

    // OpenCV (for ML branch — Zero-DCE post-processing, YOLO non-max suppression, etc.)
    // We rely on OpenCV-Android via opencv-mobile (small, prebuilt). The CI workflow
    // downloads the .aar into app/libs/ before building.
    // implementation(files("libs/opencv-mobile.aar"))
    // For first build we use a pure-Kotlin polyfill in Cv2.kt; the .aar is optional.

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Location
    implementation("com.google.android.gms:play-services-location:21.3.0")

    testImplementation("junit:junit:4.13.2")
}
