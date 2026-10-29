plugins {
    id("com.android.application")
}

android {
    namespace = "com.example.vkdl"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.vkdl"
        minSdk = 24
        targetSdk = 34
        versionCode = 2
        versionName = "2.0-java"
        resourceConfigurations += listOf("ru", "en")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += setOf(
                "/DebugProbesKt.bin",
                "/kotlin/**",
                "/META-INF/*.version",
                "/META-INF/versions/**"
            )
        }
    }
}

dependencies {
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
}
