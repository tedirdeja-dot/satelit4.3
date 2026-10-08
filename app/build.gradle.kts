plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val signingStoreFile = providers.environmentVariable("ANDROID_KEYSTORE_PATH").orNull
val signingStorePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").orNull
val signingKeyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").orNull
val signingKeyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").orNull
val signingConfigReady =
    listOf(signingStoreFile, signingStorePassword, signingKeyAlias, signingKeyPassword)
        .all { !it.isNullOrBlank() } &&
        signingStoreFile?.let { file(it).isFile } == true

android {
    namespace = "com.satellite.wallpaper"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.satellite.wallpaper"
        minSdk = 23
        targetSdk = 35
        versionCode = 22
        versionName = "4.3"
    }

    signingConfigs {
        if (signingConfigReady) {
            create("github") {
                storeFile = file(signingStoreFile!!)
                storePassword = signingStorePassword!!
                keyAlias = signingKeyAlias!!
                keyPassword = signingKeyPassword!!
            }
        }
    }

    buildTypes {
        debug {
            if (signingConfigReady) signingConfig = signingConfigs.getByName("github")
        }
        release {
            isMinifyEnabled = false
            if (signingConfigReady) signingConfig = signingConfigs.getByName("github")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
