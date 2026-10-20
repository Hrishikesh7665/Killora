plugins {
    id("com.android.application")
}

// Version number and build number live in gradle.properties (APP_VERSION_NAME / APP_VERSION_CODE).
val appVersionName: String = providers.gradleProperty("APP_VERSION_NAME").get()
val appVersionCode: Int = providers.gradleProperty("APP_VERSION_CODE").get().toInt()

android {
    namespace = "in.hrishikesh7665.killora"
    compileSdk = 37

    defaultConfig {
        applicationId = "in.hrishikesh7665.killora"
        minSdk = 24
        // Keep in step with compileSdk: shipping behind the latest platform means every device
        // on that newer OS silently applies compatibility behavior instead of this app's own.
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersionName
    }

    buildTypes {
        release {
            // Shrink and obfuscate release builds. No extra keep rules are needed: every class
            // Android instantiates by name (activities, services, receivers, the Accessibility
            // service) is declared in the manifest, and R8 keeps those automatically.
            isMinifyEnabled = true
            isShrinkResources = true
        }
    }

    buildFeatures {
        viewBinding = true
        // KLog uses BuildConfig.DEBUG to keep verbose logging out of release builds.
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    dependenciesInfo {
        includeInApk = true
        includeInBundle = true
    }
    buildToolsVersion = "36.1.0 rc1"
    ndkVersion = "28.2.13676358"
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.material)
}