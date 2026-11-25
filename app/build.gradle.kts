plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.kotarov.autoclock"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.kotarov.autoclock2"
        minSdk = 23
        targetSdk = 35
        versionCode = 77
        versionName = "0.77.0-beta"
    }

    // The public beta workflow supplies its permanent signing identity.
    // Local development and pull-request tests retain normal debug signing.
    val betaStore = System.getenv("AUTO_CLOCK_BETA_STORE")
    if (!betaStore.isNullOrBlank()) {
        val betaPassword = requireNotNull(System.getenv("AUTO_CLOCK_BETA_PASSWORD")) {
            "Missing beta signing password"
        }
        signingConfigs.getByName("debug") {
            storeFile = file(betaStore)
            storePassword = betaPassword
            keyAlias = "autoclock-beta"
            keyPassword = betaPassword
            storeType = "PKCS12"
        }
    }

    buildTypes {
        getByName("debug") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.media:media:1.7.0")
    testImplementation("junit:junit:4.13.2")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.2")
}
