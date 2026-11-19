plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.audiocpp.ttsdemo"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.audiocpp.ttsdemo"
        minSdk = (project.findProperty("audiocppMinSdk") as String?)?.toInt() ?: 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        ndk {
            abiFilters.add("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    aaptOptions {
        noCompress("gguf")
    }
}
