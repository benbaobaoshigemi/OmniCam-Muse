plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "local.jiege.camera"
    compileSdk = 37
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "local.jiege.camera"
        minSdk = 28
        targetSdk = 35
        versionCode = 300
        versionName = "Muse 4.0"
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild {
            cmake { arguments += listOf("-DANDROID_STL=c++_static") }
        }
    }

    val releaseKey = rootProject.file(providers.environmentVariable("OMNICAM_KEYSTORE").orElse("port.keystore").get())
    if (releaseKey.isFile) signingConfigs {
        create("port") {
            storeFile = releaseKey
            storePassword = System.getenv("OMNICAM_STORE_PASSWORD")
                ?: error("OMNICAM_STORE_PASSWORD is required with a release keystore")
            keyAlias = System.getenv("OMNICAM_KEY_ALIAS")
                ?: error("OMNICAM_KEY_ALIAS is required with a release keystore")
            keyPassword = System.getenv("OMNICAM_KEY_PASSWORD")
                ?: error("OMNICAM_KEY_PASSWORD is required with a release keystore")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (releaseKey.isFile) signingConfig = signingConfigs.getByName("port")
            vcsInfo.include = false
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }

    packaging {
        // LSPosed loads the native part straight from the APK (assets/native_init).
        jniLibs.useLegacyPackaging = false
    }
}

dependencies {
    compileOnly(files("libs/api-82.jar"))
    implementation("org.luckypray:dexkit:2.3.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("top.yukonga.miuix.kmp:miuix-ui-android:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-preference-android:0.9.4")
}
