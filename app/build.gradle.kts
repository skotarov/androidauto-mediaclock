import java.net.URI

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Commercial builds require a licensed Open-Meteo-compatible HTTPS gateway.
// Keep the upstream API key on the server, not in the APK.
val commercialForecastEndpoint = providers.gradleProperty("autoClockForecastEndpoint")
    .orElse(providers.environmentVariable("AUTO_CLOCK_FORECAST_ENDPOINT"))
    .orElse("").get().trim()
val escapedEndpoint = commercialForecastEndpoint.replace("\\", "\\\\").replace("\"", "\\\"")

android {
    buildFeatures { buildConfig = true }
    namespace = "com.kotarov.autoclock"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.kotarov.autoclock2"
        minSdk = 23
        targetSdk = 36
        versionCode = 81
        versionName = "0.81.0-beta"
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
        getByName("release") {
            buildConfigField("String", "FORECAST_ENDPOINT", "\"$escapedEndpoint\"")
        }
        getByName("debug") {
            buildConfigField("String", "FORECAST_ENDPOINT", "\"https://api.open-meteo.com/v1/forecast\"")
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

tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    doFirst {
        val uri = runCatching { URI(commercialForecastEndpoint) }.getOrNull()
        require(uri != null && uri.scheme == "https" && !uri.host.isNullOrBlank() &&
            !uri.host.equals("api.open-meteo.com", ignoreCase = true) &&
            uri.rawQuery == null && uri.rawFragment == null && uri.userInfo == null &&
            !uri.path.isNullOrBlank()) {
            "Commercial release requires a licensed HTTPS forecast gateway via AUTO_CLOCK_FORECAST_ENDPOINT."
        }
    }
}
