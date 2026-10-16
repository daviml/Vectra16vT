import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.turbodash.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.turbodash.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 17
        versionName = "0.4.1"
        // Trial builds: gradlew -PtrialDays=7 ... (0 = no expiry). The build time caps the trial even
        // if the app is reinstalled (first run + days, never past build + 2x days).
        val trialDays = (project.findProperty("trialDays") as String?)?.toIntOrNull() ?: 0
        buildConfigField("int", "TRIAL_DAYS", trialDays.toString())
        buildConfigField("long", "BUILD_TIME", if (trialDays > 0) "${System.currentTimeMillis()}L" else "0L")
    }

    // White label: one app, one look per brand. "costa" is a private demo for a pitch to
    // Costa Mega Speed: do not publish it without their written permission.
    flavorDimensions += "brand"
    productFlavors {
        create("turbodash") {
            dimension = "brand"
        }
        create("costa") {
            dimension = "brand"
            applicationIdSuffix = ".costa" // installs next to TurboDash
            versionNameSuffix = "-cms-demo"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
