plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.earthwheel.boost"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.earthwheel.boost"
        minSdk = 24
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // nesemnat: cheia aplicației se adaugă și APK-ul se semnează după compilare
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }


    packaging {
        resources {
            excludes += setOf(
                "META-INF/NOTICE.md", "META-INF/LICENSE.md",
                "META-INF/NOTICE.txt", "META-INF/LICENSE.txt",
                "META-INF/NOTICE", "META-INF/LICENSE",
                "META-INF/DEPENDENCIES", "META-INF/*.kotlin_module"
            )
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    // fără biblioteci externe: doar Android SDK + Kotlin
}
