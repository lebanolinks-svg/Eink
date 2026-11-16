plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.inkphone"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.inkphone"
        minSdk = 26
        targetSdk = 36
        versionCode = 11
        versionName = "2.0"
    }

    // A fixed test key kept in the repo so every build (local or CI) installs
    // over the previous one. Not a release key.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }

        // The Play upload key is never stored in the repo. Supply it with
        // -PuploadKeystore=... -PuploadStorePassword=... -PuploadKeyAlias=... -PuploadKeyPassword=...
        // or the matching ORG_GRADLE_PROJECT_* environment variables.
        val uploadKeystore = providers.gradleProperty("uploadKeystore").orNull
        if (uploadKeystore != null) {
            create("upload") {
                storeFile = file(uploadKeystore)
                storePassword = providers.gradleProperty("uploadStorePassword").get()
                keyAlias = providers.gradleProperty("uploadKeyAlias").get()
                keyPassword = providers.gradleProperty("uploadKeyPassword").get()
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("upload")
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
        buildConfig = true
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.systemProperty("robolectric.dependency.repo.url", "https://maven-central.storage-download.googleapis.com/maven2/")
        }
    }
}

dependencies {
    // One-time unlock purchase through Google Play.
    implementation("com.android.billingclient:billing:8.3.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
}
