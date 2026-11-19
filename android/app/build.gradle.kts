plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
}

android {
  namespace = "com.example.streamentity"
  compileSdk = 36
  defaultConfig {
    applicationId = "com.streamentity.app"
    minSdk = 24
    targetSdk = 36
    versionCode = 1
    versionName = "1.0"
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  buildFeatures {
    compose = true
    aidl = false
    buildConfig = false
    shaders = false
  }

  packaging {
    resources {
      excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
  }
}

kotlin {
  jvmToolchain(17)
}

dependencies {
  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  androidTestImplementation(composeBom)

  // Core Android dependencies
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)

  // Arch Components
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)

  // Compose
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)

  // Embedded Torrent Streaming Engine & Media Player
  implementation("org.libtorrent4j:libtorrent4j:2.1.0-39")
  implementation("org.libtorrent4j:libtorrent4j-android-arm64:2.1.0-39")
  implementation("org.libtorrent4j:libtorrent4j-android-arm:2.1.0-39")
  implementation("org.libtorrent4j:libtorrent4j-android-x86_64:2.1.0-39")
  implementation("org.nanohttpd:nanohttpd:2.3.1")
  implementation("org.nanohttpd:nanohttpd-websocket:2.3.1")
  implementation("androidx.media3:media3-exoplayer:1.5.0")
  implementation("androidx.media3:media3-ui:1.5.0")
  implementation("androidx.webkit:webkit:1.12.1")
  implementation("com.google.code.gson:gson:2.11.0")

  // Tooling
  debugImplementation(libs.androidx.compose.ui.tooling)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
}
