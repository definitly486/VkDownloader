fun signingValue(propertyName: String, envName: String): String {
    return providers.gradleProperty(propertyName).orNull
        ?: System.getenv(envName)
        ?: ""
}


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

 signingConfigs {
        create("release") {
            val storePath = signingValue("storeFile", "KEYSTORE_FILE")
            if (storePath != null) {
                storeFile = rootProject.file(storePath)
                storePassword = signingValue("storePassword", "KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "KEY_PASSWORD")
            }
        }

    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
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
