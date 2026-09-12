plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.android")
}

android {
  namespace = "com.gihostings.giposorder"
  compileSdk = 35

  defaultConfig {
    applicationId = "com.gihostings.giposorder"
    minSdk = 24
    targetSdk = 35
    versionCode = 1
    versionName = "1.0.0"
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      proguardFiles(
        getDefaultProguardFile("proguard-android-optimize.txt"),
        "proguard-rules.pro",
      )
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
    buildConfig = true
  }

  packaging {
    jniLibs {
      // Keep native libs page-aligned/uncompressed so they work on 16 KB page-size devices.
      useLegacyPackaging = false
    }
  }
}

dependencies {
  // CameraX for the QR scanner preview + frame analysis.
  // 1.4.x ships native libs (image_processing_util_jni) aligned for 16 KB page-size devices
  // (Android 15+), which 1.3.x did not.
  val cameraxVersion = "1.4.2"
  implementation("androidx.camera:camera-core:$cameraxVersion")
  implementation("androidx.camera:camera-camera2:$cameraxVersion")
  implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
  implementation("androidx.camera:camera-view:$cameraxVersion")

  // ZXing core for QR decoding — pure Java, no native .so, so it works fully offline and has no
  // 16 KB page-size alignment concerns (unlike ML Kit's bundled libbarhopper_v3.so).
  implementation("com.google.zxing:core:3.5.3")

  // ComponentActivity gives us a lifecycle owner + the permission request contract.
  implementation("androidx.activity:activity-ktx:1.9.3")
  implementation("androidx.core:core-ktx:1.13.1")
}
