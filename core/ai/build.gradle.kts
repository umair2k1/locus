import java.util.Properties

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
}

val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")

if (localPropertiesFile.exists()) {
    localProperties.load(localPropertiesFile.inputStream())
}

val devApiKey = localProperties.getProperty("LOCUS_DEV_API_KEY", "")
val devModel = localProperties.getProperty("LOCUS_DEV_MODEL", "gemini-3.5-flash-lite")

val hasLlamaCppSubmodule = file("src/main/cpp/llama.cpp/CMakeLists.txt").exists()

android {
    namespace = "com.locus.core.ai"
    compileSdk = 35
    ndkVersion = "26.3.11579264"

    buildFeatures { buildConfig = true }

    defaultConfig {
        minSdk = 31

        buildConfigField("String", "DEV_API_KEY", "\"$devApiKey\"")
        buildConfigField("String", "DEV_MODEL", "\"$devModel\"")

        ndk { abiFilters.addAll(listOf("arm64-v8a", "x86_64")) }

        if (hasLlamaCppSubmodule) {
            externalNativeBuild {
                cmake {
                    arguments.addAll(
                        listOf(
                            "-DCMAKE_BUILD_TYPE=Release",
                            "-DBUILD_SHARED_LIBS=OFF",
                            "-DLLAMA_OPENSSL=OFF",
                            "-DGGML_CUDA=OFF",
                            "-DGGML_METAL=OFF",
                            "-DGGML_VULKAN=OFF",
                            "-DGGML_OPENMP=OFF",
                            "-DLLAMA_BUILD_COMMON=ON",
                            "-DLLAMA_BUILD_TESTS=OFF",
                            "-DLLAMA_BUILD_TOOLS=OFF",
                            "-DLLAMA_BUILD_EXAMPLES=OFF",
                            "-DLLAMA_BUILD_SERVER=OFF",
                            "-DLLAMA_BUILD_APP=OFF",
                        ),
                    )
                }
            }
        }
    }

    if (hasLlamaCppSubmodule) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "3.22.1"
            }
        }
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
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)
    implementation(libs.serialization.json)
    implementation(libs.work.runtime.ktx)
    implementation(libs.hilt.work)
    ksp(libs.hilt.ext.compiler)
    implementation(libs.datastore.preferences)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.work.testing)
}
