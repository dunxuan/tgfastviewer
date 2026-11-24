plugins {
    id("com.android.application")
}

android {
    namespace = "io.github.dunxuan.tgfastviewer"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.dunxuan.tgfastviewer"
        minSdk = 23
        targetSdk = 36
        versionCode = 9
        versionName = "1.4.2"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    compileOnly("io.github.libxposed:api:102.0.0")
}
