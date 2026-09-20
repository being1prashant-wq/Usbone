import java.util.Base64

plugins {
  alias(libs.plugins.android.application)
}

val debugKeystoreFile = file("${rootDir}/debug.keystore")
if (!debugKeystoreFile.exists() || debugKeystoreFile.length() == 0L) {
  val base64File = file("${rootDir}/debug.keystore.base64")
  if (base64File.exists() && base64File.length() > 0L) {
    try {
      debugKeystoreFile.writeBytes(Base64.getDecoder().decode(base64File.readText().trim()))
    } catch (_: Throwable) {}
  }
}

android {
  namespace = "com.example"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.aistudio.directusb.mtptv"
    minSdk = 28
    targetSdk = 36
    versionCode = 3
    versionName = "3.0.0-vlc-ptp"
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  signingConfigs {
    create("release") {
      val keystorePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks"
      storeFile = file(keystorePath)
      storePassword = System.getenv("STORE_PASSWORD")
      keyAlias = "upload"
      keyPassword = System.getenv("KEY_PASSWORD")
    }
    create("debugConfig") {
      storeFile = file("${rootDir}/debug.keystore")
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.getByName("release")
    }
    debug { signingConfig = signingConfigs.getByName("debugConfig") }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }

  buildFeatures {
    compose = false
    buildConfig = true
  }

  testOptions { unitTests { isIncludeAndroidResources = true } }
  dependenciesInfo {
    includeInApk = false
    includeInBundle = true
  }
}

dependencies {
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.appcompat)
  implementation(libs.androidx.recyclerview)
  implementation(libs.androidx.media)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)

  // VLC 4 engine / Android bindings.
  implementation("org.videolan.android:libvlc-all:4.0.0-eap29")

  testImplementation(libs.junit)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.androidx.core)
  testImplementation(libs.robolectric)
  testImplementation(libs.kotlinx.coroutines.test)
}
