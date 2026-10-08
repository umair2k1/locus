plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.locus.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.locus.app"
        minSdk = 31
        targetSdk = 35
        versionCode = 2
        versionName = "1.2.0"
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("oss") {
            dimension = "distribution"
            buildConfigField("Boolean", "HAS_SUBSCRIPTION_ADAPTERS", "false")
        }
        create("full") {
            dimension = "distribution"
            buildConfigField("Boolean", "HAS_SUBSCRIPTION_ADAPTERS", "true")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":core:domain"))
    implementation(project(":core:data"))
    implementation(project(":core:ai"))

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.navigation)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.work.runtime.ktx)
    implementation(libs.hilt.work)
    ksp(libs.hilt.ext.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.coroutines.test)
}
