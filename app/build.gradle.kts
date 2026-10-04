plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.example.purebrowser"
    compileSdk = 36
    testBuildType = if (providers.gradleProperty("releaseSmoke").orNull == "true") "release" else "debug"
    defaultConfig {
        applicationId = "io.github.jeckchen666.purebrowser"
        minSdk = 26
        targetSdk = 36
        versionCode = 15
        versionName = "0.1.6-dev"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("releaseLocal") {
            val file = System.getenv("PB_SIGNING_STORE_FILE")
            if (!file.isNullOrBlank()) {
                storeFile = rootProject.file(file)
                storePassword = System.getenv("PB_SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("PB_SIGNING_KEY_ALIAS") ?: "purebrowser-release"
                keyPassword = System.getenv("PB_SIGNING_KEY_PASSWORD")
                storeType = "PKCS12"
            }
        }
    }
    buildTypes {
        debug { applicationIdSuffix = ".debug"; versionNameSuffix = "-debug" }
        release {
            signingConfig = signingConfigs.getByName("releaseLocal")
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

  implementation("androidx.media3:media3-extractor:1.11.1")
  implementation("androidx.media3:media3-muxer:1.11.1")

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
  // Tooling
  debugImplementation(libs.androidx.compose.ui.tooling)
  // Instrumented tests
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.test.manifest)

  // Local tests: jUnit, coroutines, Android runner
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)

  // Instrumented tests: jUnit rules and runners
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.espresso.core)

  // WebView history is handled by NavigationEvent, not the template Nav3 demo.
  implementation(libs.androidx.navigationevent.compose)
}

// Never silently publish an unsigned or Debug-signed Release.
tasks.matching { it.name == "validateSigningRelease" }.configureEach {
    doFirst {
        require(!System.getenv("PB_SIGNING_STORE_FILE").isNullOrBlank() &&
            !System.getenv("PB_SIGNING_STORE_PASSWORD").isNullOrBlank() &&
            !System.getenv("PB_SIGNING_KEY_PASSWORD").isNullOrBlank()) { "Release signing requires PB_SIGNING_* environment values; no Debug fallback." }
    }
}
