plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.xestlab.assistant"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.xestlab.assistant"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        getByName("debug") {
            val localKeystore = file("debug.keystore")
            val rootKeystore = file("${rootDir}/debug.keystore")
            when {
                rootKeystore.exists() -> {
                    storeFile = rootKeystore
                    storePassword = "android"
                    keyAlias = "androiddebugkey"
                    keyPassword = "android"
                }
                localKeystore.exists() -> {
                    storeFile = localKeystore
                    storePassword = "android"
                    keyAlias = "androiddebugkey"
                    keyPassword = "android"
                }
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
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
        compose = true
    }

    androidResources {
        noCompress += "onnx"
    }

    packaging {
        resources {
            excludes += listOf(
                "META-INF/NOTICE.md",
                "META-INF/LICENSE.md",
                "META-INF/LICENSE-notice.md"
            )
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material.icons.core)
    implementation(libs.androidx.material3)
    implementation(libs.kotlinx.coroutines.android)

    // Jetpack DataStore Preferences (First-launch User Name & Settings)
    implementation(libs.androidx.datastore.preferences)

    // EncryptedSharedPreferences for API keys, Picovoice key & IMAP credentials
    implementation(libs.androidx.security.crypto)

    // OkHttp for resilient Gemini, Groq, and ElevenLabs HTTP requests
    implementation(libs.okhttp)

    // ONNX Runtime Android for openWakeWord 3-model pipeline (melspectrogram -> embedding -> hey_jarvis_v0.1)
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.17.1")

    // Room Persistence
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // WorkManager for background scheduled tasks
    implementation(libs.androidx.work.runtime.ktx)

    // Android JavaMail for read-only IMAP email checks
    implementation(libs.sun.mail.android)
    implementation(libs.sun.activation.android)

    debugImplementation(libs.androidx.ui.tooling)

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

